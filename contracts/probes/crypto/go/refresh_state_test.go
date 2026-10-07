package crypto_test

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"sync"
	"testing"
)

type GrantRecord struct {
	GrantID   string
	DeviceID  string
	Status    string // "ACTIVE" | "REVOKED" | "EXPIRED"
	ExpiresAt float64
}

type TokenRecord struct {
	TokenHash            string
	FamilyID             string
	DeviceID             string
	IsUsed               bool
	IsRevoked            bool
	UsedAt               float64
	IssuanceOpID         string
	IssuanceBodyHash     string
	ConsumedByOpID       string
	ConsumedByBodyHash   string
}

type CachedResponse struct {
	Payload   map[string]any
	ExpiresAt float64
}

type AccessTokenRecord struct {
	TokenHash string
	FamilyID  string
	DeviceID  string
	IsRevoked bool
}

type DeviceRecord struct {
	Status  string
	GrantID string
}

type AuthControlPlaneState struct {
	mu               sync.Mutex
	devices          map[string]DeviceRecord
	grants           map[string]*GrantRecord
	tokenFamilies    map[string]bool // familyId -> isRevoked
	refreshTokens    map[string]*TokenRecord
	accessTokens     map[string]*AccessTokenRecord
	cachedResponses  map[string]*CachedResponse
	idempotencyStore map[string]string // owner:opId -> bodyHash
	auditEvents      []string
}

func newAuthState() *AuthControlPlaneState {
	return &AuthControlPlaneState{
		devices:          make(map[string]DeviceRecord),
		grants:           make(map[string]*GrantRecord),
		tokenFamilies:    make(map[string]bool),
		refreshTokens:    make(map[string]*TokenRecord),
		accessTokens:     make(map[string]*AccessTokenRecord),
		cachedResponses:  make(map[string]*CachedResponse),
		idempotencyStore: make(map[string]string),
	}
}

func generateRandomToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return "hkvpn_" + base64.RawURLEncoding.EncodeToString(b)
}

func generateUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40 // Version 4
	b[8] = (b[8] & 0x3f) | 0x80 // Variant RFC 4122
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func hashSha256(data string) string {
	h := sha256.Sum256([]byte(data))
	return hex.EncodeToString(h[:])
}

func (s *AuthControlPlaneState) enrollDevice(deviceID string, grantDuration float64, now float64) (string, string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	familyID := fmt.Sprintf("fam_%s", generateUUID())
	grantID := fmt.Sprintf("grant_%s", generateUUID())

	s.grants[grantID] = &GrantRecord{
		GrantID:   grantID,
		DeviceID:  deviceID,
		Status:    "ACTIVE",
		ExpiresAt: now + grantDuration,
	}
	s.devices[deviceID] = DeviceRecord{
		Status:  "ACTIVE",
		GrantID: grantID,
	}
	s.tokenFamilies[familyID] = false

	acc := generateRandomToken()
	ref := generateRandomToken()

	accH := hashSha256(acc)
	refH := hashSha256(ref)

	s.accessTokens[accH] = &AccessTokenRecord{
		TokenHash: accH,
		FamilyID:  familyID,
		DeviceID:  deviceID,
		IsRevoked: false,
	}
	s.refreshTokens[refH] = &TokenRecord{
		TokenHash:        refH,
		FamilyID:         familyID,
		DeviceID:         deviceID,
		IssuanceOpID:     "enroll_init",
		IssuanceBodyHash: "initial",
	}
	return acc, ref
}

func (s *AuthControlPlaneState) handleRefresh(
	deviceID string,
	idempotencyKey string,
	refreshToken string,
	clientOpID string,
	rawBody []byte,
	now float64,
) (int, map[string]any) {
	// Rule 1: Header Idempotency-Key == clientOperationId
	if idempotencyKey != clientOpID {
		return 400, map[string]any{"code": "INVALID_IDEMPOTENCY_KEY"}
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	// Rule 2: Device must be active
	dev, devExists := s.devices[deviceID]
	if !devExists || dev.Status != "ACTIVE" {
		return 401, map[string]any{"code": "DEVICE_NOT_ACTIVE"}
	}

	// Rule 3: Grant must be active and not expired
	grant, grantExists := s.grants[dev.GrantID]
	if !grantExists || grant.Status != "ACTIVE" {
		return 401, map[string]any{"code": "GRANT_INACTIVE"}
	}
	if grant.ExpiresAt <= now {
		return 401, map[string]any{"code": "GRANT_EXPIRED"}
	}

	bodyHash := hex.EncodeToString(func() []byte {
		h := sha256.Sum256(rawBody)
		return h[:]
	}())

	tokenHash := hashSha256(refreshToken)
	rec, exists := s.refreshTokens[tokenHash]
	if !exists {
		return 401, map[string]any{"code": "INVALID_TOKEN"}
	}

	// Rule 4: Device ownership verification
	// Foreign device attempting to use token must be rejected without revoking victim family
	if rec.DeviceID != deviceID {
		s.auditEvents = append(s.auditEvents, fmt.Sprintf("TOKEN_DEVICE_MISMATCH:token_dev=%s,caller=%s", rec.DeviceID, deviceID))
		return 401, map[string]any{"code": "TOKEN_DEVICE_MISMATCH"}
	}

	if s.tokenFamilies[rec.FamilyID] || rec.IsRevoked {
		return 401, map[string]any{"code": "FAMILY_REVOKED"}
	}

	idempKey := fmt.Sprintf("%s:%s", deviceID, clientOpID)

	// Check 1: Idempotency payload conflict (same opId, different body)
	if prevBodyHash, seen := s.idempotencyStore[idempKey]; seen {
		if prevBodyHash != bodyHash {
			return 409, map[string]any{"code": "IDEMPOTENCY_CONFLICT"}
		}
	}

	// Check 2: Already CONSUMED token
	if rec.IsUsed {
		if rec.ConsumedByOpID == clientOpID {
			if rec.ConsumedByBodyHash != bodyHash {
				return 409, map[string]any{"code": "IDEMPOTENCY_CONFLICT"}
			}

			// Check 120s window
			elapsed := now - rec.UsedAt
			if elapsed <= 120.0 {
				cacheKey := fmt.Sprintf("resp:%s:%s", deviceID, clientOpID)
				cached := s.cachedResponses[cacheKey]
				if cached != nil {
					res := make(map[string]any)
					for k, v := range cached.Payload {
						res[k] = v
					}
					res["_recoveredFromCache"] = true
					return 200, res
				}
				return 401, map[string]any{"code": "CACHE_UNAVAILABLE"}
			}
			return 410, map[string]any{
				"code":    "REFRESH_RETRY_EXPIRED",
				"message": "Recovery window expired",
			}
		}

		// Token Reuse Attack Detected!
		s.auditEvents = append(s.auditEvents, fmt.Sprintf("TOKEN_REUSE_DETECTED:%s", rec.FamilyID))
		s.tokenFamilies[rec.FamilyID] = true // Revoke entire family!
		for _, at := range s.accessTokens {
			if at.FamilyID == rec.FamilyID {
				at.IsRevoked = true
			}
		}
		return 401, map[string]any{
			"code":    "TOKEN_REUSED",
			"message": "Token reuse attack detected; family revoked",
		}
	}

	// Happy path: Rotate tokens
	rec.IsUsed = true
	rec.UsedAt = now
	rec.ConsumedByOpID = clientOpID
	rec.ConsumedByBodyHash = bodyHash

	newAcc := generateRandomToken()
	newRef := generateRandomToken()
	newAccH := hashSha256(newAcc)
	newRefH := hashSha256(newRef)

	s.accessTokens[newAccH] = &AccessTokenRecord{
		TokenHash: newAccH,
		FamilyID:  rec.FamilyID,
		DeviceID:  deviceID,
		IsRevoked: false,
	}
	s.refreshTokens[newRefH] = &TokenRecord{
		TokenHash:        newRefH,
		FamilyID:         rec.FamilyID,
		DeviceID:         deviceID,
		IssuanceOpID:     clientOpID,
		IssuanceBodyHash: bodyHash,
	}

	resp := map[string]any{
		"accessToken":  newAcc,
		"refreshToken": newRef,
		"expiresIn":    900,
	}

	cacheKey := fmt.Sprintf("resp:%s:%s", deviceID, clientOpID)
	s.cachedResponses[cacheKey] = &CachedResponse{
		Payload:   resp,
		ExpiresAt: now + 120.0,
	}
	s.idempotencyStore[idempKey] = bodyHash

	return 200, resp
}

func (s *AuthControlPlaneState) handleReauth(
	deviceID string,
	clientOpID string,
	rawBody []byte,
	now float64,
) (int, map[string]any) {
	s.mu.Lock()
	defer s.mu.Unlock()

	dev, devExists := s.devices[deviceID]
	if !devExists || dev.Status != "ACTIVE" {
		return 401, map[string]any{"code": "DEVICE_NOT_FOUND"}
	}

	grant, grantExists := s.grants[dev.GrantID]
	if !grantExists || grant.Status != "ACTIVE" {
		return 401, map[string]any{"code": "GRANT_INACTIVE", "message": "Grant is revoked or missing"}
	}
	if grant.ExpiresAt <= now {
		return 401, map[string]any{"code": "GRANT_EXPIRED", "message": "Grant has expired"}
	}

	bodyHash := ""
	if len(rawBody) > 0 {
		bodyHash = hashSha256(string(rawBody))
	} else {
		bodyHash = "reauth_body"
	}

	// Idempotency check if clientOpID provided
	if clientOpID != "" {
		idempKey := fmt.Sprintf("%s:%s", deviceID, clientOpID)
		if prevBodyHash, seen := s.idempotencyStore[idempKey]; seen {
			if prevBodyHash != bodyHash {
				return 409, map[string]any{"code": "IDEMPOTENCY_CONFLICT"}
			}
			cacheKey := fmt.Sprintf("resp:%s:%s", deviceID, clientOpID)
			cached := s.cachedResponses[cacheKey]
			if cached != nil {
				if now <= cached.ExpiresAt {
					res := make(map[string]any)
					for k, v := range cached.Payload {
						res[k] = v
					}
					res["_recoveredFromCache"] = true
					return 200, res
				}
				return 410, map[string]any{
					"code":    "REFRESH_RETRY_EXPIRED",
					"message": "Recovery window expired",
				}
			}
		}
	}

	// Grant preservation: retain same grant ID and original expiry
	grantID := dev.GrantID
	grantExpiry := grant.ExpiresAt

	// Revoke all existing token families and active tokens for this device
	for famID := range s.tokenFamilies {
		for _, rec := range s.refreshTokens {
			if rec.DeviceID == deviceID && rec.FamilyID == famID {
				s.tokenFamilies[famID] = true
				break
			}
		}
	}
	for _, rec := range s.refreshTokens {
		if rec.DeviceID == deviceID {
			rec.IsRevoked = true
		}
	}
	for _, at := range s.accessTokens {
		if at.DeviceID == deviceID {
			at.IsRevoked = true
		}
	}

	// Issue ONE new family bound to the same grant
	newFamilyID := fmt.Sprintf("fam_%s", generateUUID())
	s.tokenFamilies[newFamilyID] = false

	newAcc := generateRandomToken()
	newRef := generateRandomToken()

	newAccH := hashSha256(newAcc)
	newRefH := hashSha256(newRef)

	s.accessTokens[newAccH] = &AccessTokenRecord{
		TokenHash: newAccH,
		FamilyID:  newFamilyID,
		DeviceID:  deviceID,
		IsRevoked: false,
	}

	opID := clientOpID
	if opID == "" {
		opID = "reauth_init"
	}

	s.refreshTokens[newRefH] = &TokenRecord{
		TokenHash:        newRefH,
		FamilyID:         newFamilyID,
		DeviceID:         deviceID,
		IssuanceOpID:     opID,
		IssuanceBodyHash: bodyHash,
	}

	resp := map[string]any{
		"accessToken":    newAcc,
		"refreshToken":   newRef,
		"grantId":        grantID,
		"grantExpiresAt": grantExpiry,
		"reauthSuccess":  true,
		"expiresIn":      900,
	}

	if clientOpID != "" {
		cacheKey := fmt.Sprintf("resp:%s:%s", deviceID, clientOpID)
		s.cachedResponses[cacheKey] = &CachedResponse{
			Payload:   resp,
			ExpiresAt: now + 120.0,
		}
		idempKey := fmt.Sprintf("%s:%s", deviceID, clientOpID)
		s.idempotencyStore[idempKey] = bodyHash
	}

	return 200, resp
}

func (s *AuthControlPlaneState) handleDeleteDevice(deviceID string, idempHeader *string) (int, map[string]any) {
	if idempHeader != nil {
		return 400, map[string]any{"code": "IDEMPOTENCY_KEY_NOT_PERMITTED"}
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	dev, devExists := s.devices[deviceID]
	if !devExists || dev.Status != "ACTIVE" {
		return 410, map[string]any{"code": "DEVICE_ALREADY_REVOKED"}
	}
	s.devices[deviceID] = DeviceRecord{
		Status:  "REVOKED",
		GrantID: dev.GrantID,
	}
	if grant, exists := s.grants[dev.GrantID]; exists {
		grant.Status = "REVOKED"
	}
	for rec := range s.refreshTokens {
		if s.refreshTokens[rec].DeviceID == deviceID {
			s.tokenFamilies[s.refreshTokens[rec].FamilyID] = true
			s.refreshTokens[rec].IsRevoked = true
		}
	}
	for _, at := range s.accessTokens {
		if at.DeviceID == deviceID {
			at.IsRevoked = true
		}
	}
	return 204, nil
}

func TestLostResponseRefreshStateProbe(t *testing.T) {
	state := newAuthState()
	deviceID := "test_dev_go_9ecbb43ac6fbda663bd81db7f89b50b955692453"

	// 1. Initial enrollment
	_, ref1 := state.enrollDevice(deviceID, 86400, 1700000000)

	// 2. Initial rotation
	t0 := float64(1700000000)
	op1 := "op-go-refresh-1001"
	body1 := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, ref1, op1))

	status, resp1 := state.handleRefresh(deviceID, op1, ref1, op1, body1, t0)
	if status != 200 {
		t.Fatalf("expected 200 on initial refresh, got %d", status)
	}
	ref2 := resp1["refreshToken"].(string)

	// 3. Exact Retry within 120s
	tRetry := t0 + 30.0
	statusRetry, respRetry := state.handleRefresh(deviceID, op1, ref1, op1, body1, tRetry)
	if statusRetry != 200 {
		t.Fatalf("expected 200 on exact retry, got %d", statusRetry)
	}
	if respRetry["_recoveredFromCache"] != true {
		t.Fatalf("expected _recoveredFromCache == true")
	}
	if respRetry["refreshToken"] != ref2 {
		t.Fatalf("expected same refreshToken, got %v", respRetry["refreshToken"])
	}

	// Verify token family is NOT revoked
	ref2Hash := hashSha256(ref2)
	rec2 := state.refreshTokens[ref2Hash]
	if rec2.IsUsed || rec2.IsRevoked {
		t.Fatalf("successor token must be active and not revoked")
	}

	// 4. Payload Conflict (409)
	altBody := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s","tamper":true}`, ref1, op1))
	statusConflict, _ := state.handleRefresh(deviceID, op1, ref1, op1, altBody, t0+35.0)
	if statusConflict != 409 {
		t.Fatalf("expected 409 Conflict, got %d", statusConflict)
	}

	// 5. Cross-Device Refresh Rejection (foreign device trying to refresh device A's token)
	devB := "test_dev_b_attacker_go"
	state.enrollDevice(devB, 86400, t0)
	opCross := "op-cross-device-go"
	bodyCross := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, ref2, opCross))
	statusCross, respCross := state.handleRefresh(devB, opCross, ref2, opCross, bodyCross, t0+38.0)
	if statusCross != 401 || respCross["code"] != "TOKEN_DEVICE_MISMATCH" {
		t.Fatalf("expected 401 TOKEN_DEVICE_MISMATCH for cross-device probe, got %d (%v)", statusCross, respCross)
	}
	// Verify victim's family is NOT revoked
	if state.tokenFamilies[rec2.FamilyID] {
		t.Fatalf("victim token family must NOT be revoked on foreign device probe")
	}

	// 6. Token reuse attack (consumed token + new opId on original device)
	attackerOp := "op-attacker-evil-go"
	attackerBody := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, ref1, attackerOp))
	statusReuse, respReuse := state.handleRefresh(deviceID, attackerOp, ref1, attackerOp, attackerBody, t0+40.0)
	if statusReuse != 401 || respReuse["code"] != "TOKEN_REUSED" {
		t.Fatalf("expected 401 TOKEN_REUSED, got %d (%v)", statusReuse, respReuse)
	}

	// Legitimate client with ref2 must now fail
	statusFamRev, _ := state.handleRefresh(deviceID, "op-next", ref2, "op-next", []byte(`{}`), t0+45.0)
	if statusFamRev != 401 {
		t.Fatalf("expected 401 FAMILY_REVOKED, got %d", statusFamRev)
	}

	// 7. Expired recovery window (> 120s)
	dev2 := "test_dev2_expired_go"
	_, ref3 := state.enrollDevice(dev2, 86400, 1700001000)
	t1 := float64(1700001000)
	opExp := "op-exp-go-01"
	bodyExp := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, ref3, opExp))

	state.handleRefresh(dev2, opExp, ref3, opExp, bodyExp, t1)

	// Retry at 130s (> 120s)
	statusLate, respLate := state.handleRefresh(dev2, opExp, ref3, opExp, bodyExp, t1+130.0)
	if statusLate != 410 || respLate["code"] != "REFRESH_RETRY_EXPIRED" {
		t.Fatalf("expected 410 REFRESH_RETRY_EXPIRED, got %d (%v)", statusLate, respLate)
	}

	// Reauth recovery with grant preservation
	statusReauth, respReauth := state.handleReauth(dev2, "op-reauth-init-go", []byte(`{}`), t1+135.0)
	if statusReauth != 200 || respReauth["reauthSuccess"] != true {
		t.Fatalf("expected 200 reauth success, got %d", statusReauth)
	}
	if respReauth["grantId"] != state.devices[dev2].GrantID {
		t.Fatalf("expected preserved grantId %s, got %v", state.devices[dev2].GrantID, respReauth["grantId"])
	}

	// 8. Grant Expiration
	devGrant := "test_dev_grant_exp_go"
	_, refG := state.enrollDevice(devGrant, 60, t0) // 60s grant
	opG := "op-grant-exp-go"
	bodyG := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, refG, opG))
	statusGrantExp, respGrantExp := state.handleRefresh(devGrant, opG, refG, opG, bodyG, t0+65.0)
	if statusGrantExp != 401 || respGrantExp["code"] != "GRANT_EXPIRED" {
		t.Fatalf("expected 401 GRANT_EXPIRED, got %d (%v)", statusGrantExp, respGrantExp)
	}

	// 9. DELETE /v1/devices/me natural idempotency
	dev3 := "test_dev3_delete_go"
	state.enrollDevice(dev3, 86400, t0)

	badH := "header-key"
	statusBadDel, _ := state.handleDeleteDevice(dev3, &badH)
	if statusBadDel != 400 {
		t.Fatalf("expected 400 on DELETE with header, got %d", statusBadDel)
	}

	statusDel1, _ := state.handleDeleteDevice(dev3, nil)
	if statusDel1 != 204 {
		t.Fatalf("expected 204 on first DELETE, got %d", statusDel1)
	}

	statusDel2, _ := state.handleDeleteDevice(dev3, nil)
	if statusDel2 != 410 {
		t.Fatalf("expected 410 on repeated DELETE, got %d", statusDel2)
	}

	// 10. F01: Active device + Revoked grant -> MUST REJECT
	tReauthBase := float64(1700010000)
	devRevG := "test_dev_f01_rev_grant_go"
	state.enrollDevice(devRevG, 3600, tReauthBase)
	revGID := state.devices[devRevG].GrantID
	state.grants[revGID].Status = "REVOKED"
	sRevG, rRevG := state.handleReauth(devRevG, "op-rev-g", nil, tReauthBase+10.0)
	if sRevG != 401 || rRevG["code"] != "GRANT_INACTIVE" {
		t.Fatalf("expected 401 GRANT_INACTIVE on revoked grant, got %d (%v)", sRevG, rRevG)
	}

	// 11. F01: Active device + Expired grant -> MUST REJECT
	devExpG := "test_dev_f01_exp_grant_go"
	state.enrollDevice(devExpG, 60, tReauthBase)
	sExpG, rExpG := state.handleReauth(devExpG, "op-exp-g", nil, tReauthBase+65.0)
	if sExpG != 401 || rExpG["code"] != "GRANT_EXPIRED" {
		t.Fatalf("expected 401 GRANT_EXPIRED on expired grant, got %d (%v)", sExpG, rExpG)
	}

	// 12. F01: Missing grant -> MUST REJECT
	devMisG := "test_dev_f01_mis_grant_go"
	state.enrollDevice(devMisG, 3600, tReauthBase)
	state.devices[devMisG] = DeviceRecord{Status: "ACTIVE", GrantID: "grant_nonexistent_xyz"}
	sMisG, rMisG := state.handleReauth(devMisG, "op-mis-g", nil, tReauthBase+10.0)
	if sMisG != 401 || rMisG["code"] != "GRANT_INACTIVE" {
		t.Fatalf("expected 401 GRANT_INACTIVE on missing grant, got %d (%v)", sMisG, rMisG)
	}

	// 13. F01: Valid short grant without extension -> preserves identical grant ID and expiry
	devShortG := "test_dev_f01_short_grant_go"
	_, refS0 := state.enrollDevice(devShortG, 500, tReauthBase)
	origGID := state.devices[devShortG].GrantID
	origExpiry := state.grants[origGID].ExpiresAt
	refS0Hash := hashSha256(refS0)
	oldFamID := state.refreshTokens[refS0Hash].FamilyID

	sValid, rValid := state.handleReauth(devShortG, "op-short-reauth-1", []byte(`{"clientOperationId":"op-short-reauth-1"}`), tReauthBase+100.0)
	if sValid != 200 {
		t.Fatalf("expected 200 on valid reauth, got %d", sValid)
	}
	if rValid["grantId"] != origGID {
		t.Fatalf("grant ID must remain strictly identical: %v vs %v", rValid["grantId"], origGID)
	}
	if rValid["grantExpiresAt"] != origExpiry {
		t.Fatalf("grant expiry must NOT be extended: %v vs %v", rValid["grantExpiresAt"], origExpiry)
	}
	if state.grants[origGID].ExpiresAt != origExpiry {
		t.Fatalf("stored grant expiry must remain unchanged")
	}
	if !state.tokenFamilies[oldFamID] {
		t.Fatalf("old family must be revoked")
	}

	// Old refresh token must fail with FAMILY_REVOKED
	sOldRef, rOldRef := state.handleRefresh(devShortG, "op-old-try", refS0, "op-old-try", []byte(`{}`), tReauthBase+110.0)
	if sOldRef != 401 || rOldRef["code"] != "FAMILY_REVOKED" {
		t.Fatalf("expected 401 FAMILY_REVOKED on old token, got %d (%v)", sOldRef, rOldRef)
	}

	// 14. F01: Lost-response retry of reauth within 120s returns cached response
	sRetry, rRetry := state.handleReauth(devShortG, "op-short-reauth-1", []byte(`{"clientOperationId":"op-short-reauth-1"}`), tReauthBase+130.0)
	if sRetry != 200 || rRetry["_recoveredFromCache"] != true {
		t.Fatalf("expected 200 with _recoveredFromCache, got %d (%v)", sRetry, rRetry)
	}
	if rRetry["refreshToken"] != rValid["refreshToken"] {
		t.Fatalf("expected identical refresh token from cache")
	}

	// 15. F01: Reauth idempotency conflict (altered payload) -> 409
	sConf, rConf := state.handleReauth(devShortG, "op-short-reauth-1", []byte(`{"tampered":true}`), tReauthBase+140.0)
	if sConf != 409 || rConf["code"] != "IDEMPOTENCY_CONFLICT" {
		t.Fatalf("expected 409 IDEMPOTENCY_CONFLICT, got %d (%v)", sConf, rConf)
	}

	// 16. F01: Same-second successive reauths create distinct families cleanly
	sS1, rS1 := state.handleReauth(devShortG, "", nil, tReauthBase+200.0)
	sS2, rS2 := state.handleReauth(devShortG, "", nil, tReauthBase+200.0)
	if sS1 != 200 || sS2 != 200 {
		t.Fatalf("expected 200 on same-second reauths, got %d, %d", sS1, sS2)
	}
	hS1 := hashSha256(rS1["refreshToken"].(string))
	hS2 := hashSha256(rS2["refreshToken"].(string))
	famS1 := state.refreshTokens[hS1].FamilyID
	famS2 := state.refreshTokens[hS2].FamilyID
	if famS1 == famS2 {
		t.Fatalf("successive reauths must yield distinct families")
	}
	if !state.tokenFamilies[famS1] {
		t.Fatalf("predecessor family must be revoked")
	}
	if state.tokenFamilies[famS2] {
		t.Fatalf("latest family must be active")
	}

	// 17. F01: Device ID prefix isolation
	devPfx := "test_dev_prefix_go"
	devPfxLong := "test_dev_prefix_longer_go"
	state.enrollDevice(devPfx, 3600, tReauthBase)
	state.enrollDevice(devPfxLong, 3600, tReauthBase)
	sPfx, _ := state.handleReauth(devPfx, "", nil, tReauthBase+10.0)
	if sPfx != 200 {
		t.Fatalf("expected 200 on devPfx reauth, got %d", sPfx)
	}
	longGID := state.devices[devPfxLong].GrantID
	if state.grants[longGID].Status != "ACTIVE" {
		t.Fatalf("longer device grant must remain untouched")
	}

	// 18. F01: Reauth after terminal DELETE -> strictly rejected
	sAfterDel, _ := state.handleReauth(dev3, "", nil, tReauthBase+300.0)
	if sAfterDel != 401 {
		t.Fatalf("expected 401 after delete, got %d", sAfterDel)
	}
}

func TestConcurrentFirstRefreshRace(t *testing.T) {
	state := newAuthState()
	deviceID := "test_dev_concurrent_first_race"
	tBase := float64(1700005000)

	_, refInit := state.enrollDevice(deviceID, 86400, tBase)
	opRace := "op-concurrent-first-refresh"
	bodyRace := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, refInit, opRace))

	const numWorkers = 10
	var startWg sync.WaitGroup
	var doneWg sync.WaitGroup
	startWg.Add(1)

	type workerResult struct {
		status int
		ref    string
		cached bool
	}
	results := make([]workerResult, numWorkers)

	for i := 0; i < numWorkers; i++ {
		doneWg.Add(1)
		go func(idx int) {
			defer doneWg.Done()
			startWg.Wait() // Barrier: all workers wait until released simultaneously

			status, resp := state.handleRefresh(deviceID, opRace, refInit, opRace, bodyRace, tBase+1.0)
			recovered, _ := resp["_recoveredFromCache"].(bool)
			refStr, _ := resp["refreshToken"].(string)
			results[idx] = workerResult{
				status: status,
				ref:    refStr,
				cached: recovered,
			}
		}(i)
	}

	// Release all workers simultaneously
	startWg.Done()
	doneWg.Wait()

	// Verify exactly ONE successor branch was issued in state.refreshTokens
	state.mu.Lock()
	var successorCount int
	for _, rec := range state.refreshTokens {
		if rec.DeviceID == deviceID && rec.IssuanceOpID == opRace {
			successorCount++
		}
	}
	state.mu.Unlock()

	if successorCount != 1 {
		t.Fatalf("expected exactly 1 successor token in state, got %d", successorCount)
	}

	var expectedRef string
	for idx, r := range results {
		if r.status != 200 {
			t.Fatalf("worker %d got non-200 status: %d", idx, r.status)
		}
		if expectedRef == "" {
			expectedRef = r.ref
		} else if r.ref != expectedRef {
			t.Fatalf("worker %d got divergent refresh token %s vs %s", idx, r.ref, expectedRef)
		}
	}
}

func TestConcurrentRefreshRaces(t *testing.T) {
	state := newAuthState()
	deviceID := "test_dev_concurrent_race"
	tBase := float64(1700005000)

	_, refInit := state.enrollDevice(deviceID, 86400, tBase)
	opRace := "op-concurrent-exact-retry"
	bodyRace := []byte(fmt.Sprintf(`{"refreshToken":"%s","clientOperationId":"%s"}`, refInit, opRace))

	// Initial rotation
	statusInit, respInit := state.handleRefresh(deviceID, opRace, refInit, opRace, bodyRace, tBase)
	if statusInit != 200 {
		t.Fatalf("failed initial rotation: %d", statusInit)
	}
	expectedRef := respInit["refreshToken"].(string)

	// Launch 20 concurrent retries of the exact same request
	const numWorkers = 20
	var wg sync.WaitGroup
	results := make([]struct {
		status int
		ref    string
		cached bool
	}, numWorkers)

	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			status, resp := state.handleRefresh(deviceID, opRace, refInit, opRace, bodyRace, tBase+10.0)
			recovered, _ := resp["_recoveredFromCache"].(bool)
			refStr, _ := resp["refreshToken"].(string)
			results[idx] = struct {
				status int
				ref    string
				cached bool
			}{
				status: status,
				ref:    refStr,
				cached: recovered,
			}
		}(i)
	}

	wg.Wait()

	for idx, r := range results {
		if r.status != 200 {
			t.Fatalf("worker %d got non-200 status: %d", idx, r.status)
		}
		if r.ref != expectedRef {
			t.Fatalf("worker %d got unexpected refresh token: %s (expected %s)", idx, r.ref, expectedRef)
		}
		if !r.cached {
			t.Fatalf("worker %d did not get cached response", idx)
		}
	}
}
