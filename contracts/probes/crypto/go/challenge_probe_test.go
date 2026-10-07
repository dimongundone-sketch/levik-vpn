package crypto_test

import (
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type ChallengeVectorsDoc struct {
	Version         string                    `json:"version"`
	Description     string                    `json:"description"`
	ServerKey       KeyDoc                    `json:"serverKey"`
	RogueServerKey  KeyDoc                    `json:"rogueServerKey"`
	PositiveVectors []PositiveChallengeVector `json:"positiveVectors"`
	NegativeVectors []NegativeChallengeVector `json:"negativeVectors"`
}

type PositiveChallengeVector struct {
	ID                  string          `json:"id"`
	Description         string          `json:"description"`
	RequestBody         json.RawMessage `json:"requestBody"`
	RequestBodyRawBytes string          `json:"requestBodyRawBytes"`
	CanonicalString     string          `json:"canonicalString"`
	Response            ChallengeRespDoc `json:"response"`
}

type NegativeChallengeVector struct {
	ID                  string          `json:"id"`
	Description         string          `json:"description"`
	RequestBody         json.RawMessage `json:"requestBody"`
	RequestBodyRawBytes string          `json:"requestBodyRawBytes"`
	CanonicalString     string          `json:"canonicalString"`
	Response            ChallengeRespDoc `json:"response"`
	ExpectedError       string          `json:"expectedError"`
}

type ChallengeRespDoc struct {
	ChallengeID        string `json:"challengeId"`
	ServerNonce        string `json:"serverNonce"`
	ExpiresAt          string `json:"expiresAt"`
	ServerTime         string `json:"serverTime"`
	KeyID              string `json:"keyId"`
	Purpose            string `json:"purpose"`
	SignatureAlgorithm string `json:"signatureAlgorithm"`
	RequestBodyHash    string `json:"requestBodyHash"`
	Signature          string `json:"signature"`
}

func parseECDSAPublicKey(pemStr string) (*ecdsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("failed to parse PEM block")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ecPub, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not ECDSA public key")
	}
	return ecPub, nil
}

func verifyChallengeResponse(
	resp ChallengeRespDoc,
	rawReqBytes []byte,
	trustedRing map[string]*ecdsa.PublicKey,
	clientNow time.Time,
) (string, error) {
	// 1. Required fields
	if resp.Signature == "" {
		return "MISSING_SIGNATURE", fmt.Errorf("missing signature")
	}
	if resp.Purpose != "challenge" {
		return "INVALID_PURPOSE", fmt.Errorf("invalid purpose: %s", resp.Purpose)
	}
	if resp.SignatureAlgorithm != "ES256" {
		return "UNSUPPORTED_SIGNATURE_ALGORITHM", fmt.Errorf("unsupported alg: %s", resp.SignatureAlgorithm)
	}

	// 2. Client nonce length check from request body
	var reqMap map[string]interface{}
	if err := json.Unmarshal(rawReqBytes, &reqMap); err == nil {
		if cNonce, ok := reqMap["clientNonce"].(string); ok {
			if len(cNonce) != 22 {
				return "INVALID_NONCE_LENGTH", fmt.Errorf("client nonce wrong length: %d", len(cNonce))
			}
			rawDec, err := base64.RawURLEncoding.DecodeString(cNonce)
			if err != nil || len(rawDec) != 16 {
				return "INVALID_NONCE_LENGTH", fmt.Errorf("client nonce not 16 bytes")
			}
		}
	}

	// 3. Server nonce length check (32 bytes = 43 chars base64url)
	sNonceDec, err := base64.RawURLEncoding.DecodeString(resp.ServerNonce)
	if err != nil || len(sNonceDec) != 32 {
		return "INVALID_SERVER_NONCE_LENGTH", fmt.Errorf("server nonce not 32 bytes")
	}

	// 4. Request body hash verification
	hashedReq := sha256.Sum256(rawReqBytes)
	expectedHashHex := fmt.Sprintf("%x", hashedReq)
	if resp.RequestBodyHash != expectedHashHex {
		return "REQUEST_BODY_HASH_MISMATCH", fmt.Errorf("requestBodyHash mismatch")
	}

	// 5. Trusted key ring check
	trustedPub, ok := trustedRing[resp.KeyID]
	if !ok {
		return "UNAUTHORIZED_SIGNING_KEY", fmt.Errorf("unauthorized signing key: %s", resp.KeyID)
	}

	// 6. Decode signature and verify strict canonical low-S
	sigBytes, err := base64.RawURLEncoding.DecodeString(resp.Signature)
	if err != nil {
		return "CHALLENGE_SIGNATURE_INVALID", fmt.Errorf("base64 decode sig: %w", err)
	}

	var dsaSig struct {
		R, S *big.Int
	}
	if rest, err := asn1.Unmarshal(sigBytes, &dsaSig); err != nil || len(rest) > 0 {
		return "CHALLENGE_SIGNATURE_INVALID", fmt.Errorf("invalid ASN.1 DER")
	}

	if dsaSig.S.Cmp(halfOrder) > 0 {
		return "CHALLENGE_SIGNATURE_INVALID", fmt.Errorf("high-S signature rejected")
	}

	// 7. Canonical string assembly and ECDSA verification
	canonicalStr := fmt.Sprintf(
		"HKVPN-CHALLENGE-V1\nchallenge\nES256\n%s\n%s\n%s\n%s\n%s\n%s\n",
		resp.KeyID,
		resp.ChallengeID,
		resp.ServerNonce,
		resp.ExpiresAt,
		resp.ServerTime,
		resp.RequestBodyHash,
	)
	hashedCanon := sha256.Sum256([]byte(canonicalStr))
	if !ecdsa.VerifyASN1(trustedPub, hashedCanon[:], sigBytes) {
		return "CHALLENGE_SIGNATURE_INVALID", fmt.Errorf("ecdsa verification failed")
	}

	// 8. Timestamp validity and expiry
	serverTime, err := time.Parse(time.RFC3339, resp.ServerTime)
	if err != nil {
		return "INVALID_TIMESTAMP_FORMAT", fmt.Errorf("parse serverTime: %w", err)
	}
	expiresAt, err := time.Parse(time.RFC3339, resp.ExpiresAt)
	if err != nil {
		return "INVALID_TIMESTAMP_FORMAT", fmt.Errorf("parse expiresAt: %w", err)
	}

	if !serverTime.Before(expiresAt) {
		return "CHALLENGE_EXPIRED", fmt.Errorf("challenge expired: serverTime >= expiresAt")
	}

	// 9. Clock drift check (<= 86400s)
	drift := clientNow.Sub(serverTime)
	if drift < 0 {
		drift = -drift
	}
	if drift > 24*time.Hour {
		return "CLOCK_DRIFT_EXCESSIVE", fmt.Errorf("excessive clock drift: %v", drift)
	}

	return "OK", nil
}

func TestChallengeProtocolVectors(t *testing.T) {
	// Locate challenge-vectors.json
	repoRoot := filepath.Join("..", "..", "..")
	vectorsPath := filepath.Join(repoRoot, "challenge-vectors.json")
	if _, err := os.Stat(vectorsPath); os.IsNotExist(err) {
		// Try from contracts/
		vectorsPath = filepath.Join("..", "..", "..", "contracts", "challenge-vectors.json")
	}

	data, err := os.ReadFile(vectorsPath)
	if err != nil {
		t.Fatalf("Failed to read challenge-vectors.json: %v", err)
	}

	var doc ChallengeVectorsDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("Failed to unmarshal challenge-vectors.json: %v", err)
	}

	serverPub, err := parseECDSAPublicKey(doc.ServerKey.PublicKeyPem)
	if err != nil {
		t.Fatalf("Failed to parse server public key: %v", err)
	}

	trustedRing := map[string]*ecdsa.PublicKey{
		doc.ServerKey.KeyID: serverPub,
	}

	clientNow, err := time.Parse(time.RFC3339, "2026-10-07T08:30:00Z")
	if err != nil {
		t.Fatalf("Failed to parse client reference time: %v", err)
	}

	t.Run("PositiveVectors", func(t *testing.T) {
		for _, pv := range doc.PositiveVectors {
			t.Run(pv.ID, func(t *testing.T) {
				status, err := verifyChallengeResponse(pv.Response, []byte(pv.RequestBodyRawBytes), trustedRing, clientNow)
				if err != nil {
					t.Fatalf("Positive vector %s failed with status %s: %v", pv.ID, status, err)
				}
				if status != "OK" {
					t.Fatalf("Expected OK, got %s", status)
				}
			})
		}
	})

	t.Run("NegativeVectors", func(t *testing.T) {
		for _, nv := range doc.NegativeVectors {
			t.Run(nv.ID, func(t *testing.T) {
				status, err := verifyChallengeResponse(nv.Response, []byte(nv.RequestBodyRawBytes), trustedRing, clientNow)
				if err == nil && status == "OK" {
					t.Fatalf("Negative vector %s unexpectedly passed!", nv.ID)
				}
				if status != nv.ExpectedError {
					t.Fatalf("Negative vector %s: expected error %s, got %s (err: %v)", nv.ID, nv.ExpectedError, status, err)
				}
			})
		}
	})
}
