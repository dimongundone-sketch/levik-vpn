package crypto_test

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"testing"
	"time"
)

type EnvelopeVectorsDoc struct {
	Version         string                   `json:"version"`
	ServerKey       KeyDoc                   `json:"serverKey"`
	RogueServerKey  KeyDoc                   `json:"rogueServerKey"`
	DeviceKeys      map[string]DeviceKeyDoc  `json:"deviceKeys"`
	PositiveVectors []PositiveEnvelopeVector `json:"positiveVectors"`
	NegativeVectors []NegativeEnvelopeVector `json:"negativeVectors"`
}

type KeyDoc struct {
	KeyID              string `json:"keyId"`
	Curve              string `json:"curve"`
	PublicKeyPem       string `json:"publicKeyPem"`
	PrivateKeyPkcs8Pem string `json:"privateKeyPkcs8Pem"`
}

type DeviceKeyDoc struct {
	KeyID              string `json:"keyId"`
	EncAlgorithm       string `json:"encAlgorithm"`
	DeviceID           string `json:"deviceId"`
	PublicKeyPem       string `json:"publicKeyPem"`
	PrivateKeyPkcs8Pem string `json:"privateKeyPkcs8Pem"`
}

type PositiveEnvelopeVector struct {
	ID                 string          `json:"id"`
	Description        string          `json:"description"`
	EncAlgorithm       string          `json:"encAlgorithm"`
	SignatureAlgorithm string          `json:"signatureAlgorithm"`
	TargetDeviceRef    string          `json:"targetDeviceRef"`
	Plaintext          json.RawMessage `json:"plaintext"`
	Envelope           EnvelopeDoc     `json:"envelope"`
}

type NegativeEnvelopeVector struct {
	ID                   string      `json:"id"`
	Description          string      `json:"description"`
	Type                 string      `json:"type"`
	ExpectedError        string      `json:"expectedError"`
	TargetDeviceRef      string      `json:"targetDeviceRef"`
	LastObservedRevision int         `json:"lastObservedRevision"`
	Envelope             EnvelopeDoc `json:"envelope"`
}

type EnvelopeDoc struct {
	Protected  string `json:"protected"`
	WrappedKey string `json:"wrappedKey"`
	IV         string `json:"iv"`
	Ciphertext string `json:"ciphertext"`
	Signature  string `json:"signature"`
}

type ProtectedMetadata struct {
	Purpose             string `json:"purpose"`
	SchemaVersion       int    `json:"schemaVersion"`
	KeyID               string `json:"keyId"`
	SignatureAlgorithm  string `json:"signatureAlgorithm"`
	EncAlgorithm        string `json:"encAlgorithm"`
	DeviceID            string `json:"deviceId"`
	AccessID            string `json:"accessId"`
	ProfileID           string `json:"profileId"`
	ProfileRevision     int    `json:"profileRevision"`
	IssuedAt            string `json:"issuedAt"`
	CredentialExpiresAt string `json:"credentialExpiresAt"`
}

var halfOrder = new(big.Int).Div(elliptic.P256().Params().N, big.NewInt(2))

func parseECPublicKey(pemStr string) (*ecdsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ecPub, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not an ECDSA public key")
	}
	return ecPub, nil
}

func parseRSAPrivateKey(pemStr string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM")
	}
	priv, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	rsaPriv, ok := priv.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("not an RSA private key")
	}
	return rsaPriv, nil
}

func verifyServerECDSASignature(serverPub, roguePub *ecdsa.PublicKey, env EnvelopeDoc) (string, error) {
	if env.Signature == "" {
		return "MISSING_SIGNATURE", fmt.Errorf("missing signature")
	}
	sigBytes, err := base64.RawURLEncoding.DecodeString(env.Signature)
	if err != nil {
		return "ENVELOPE_SIGNATURE_INVALID", fmt.Errorf("base64 decode signature: %w", err)
	}

	var dsaSig struct {
		R, S *big.Int
	}
	if rest, err := asn1.Unmarshal(sigBytes, &dsaSig); err != nil || len(rest) > 0 {
		return "ENVELOPE_SIGNATURE_INVALID", fmt.Errorf("invalid ASN.1 DER signature")
	}

	// Canonical low-S check
	if dsaSig.S.Cmp(halfOrder) > 0 {
		return "ENVELOPE_SIGNATURE_INVALID", fmt.Errorf("high-S signature rejected")
	}

	signedStr := fmt.Sprintf("HKVPN-PROFILE-V2\n%s\n%s\n%s\n%s\n", env.Protected, env.WrappedKey, env.IV, env.Ciphertext)
	hashed := sha256.Sum256([]byte(signedStr))

	if ecdsa.VerifyASN1(serverPub, hashed[:], sigBytes) {
		return "OK", nil
	}

	if roguePub != nil && ecdsa.VerifyASN1(roguePub, hashed[:], sigBytes) {
		return "UNTRUSTED_KEY_SIGNATURE", fmt.Errorf("untrusted server key")
	}

	return "ENVELOPE_SIGNATURE_INVALID", fmt.Errorf("invalid signature")
}

func unwrapAESKey(devicePriv *rsa.PrivateKey, encAlg string, wrappedKeyB64 string) ([]byte, error) {
	wrappedBytes, err := base64.RawURLEncoding.DecodeString(wrappedKeyB64)
	if err != nil {
		return nil, fmt.Errorf("base64 decode wrapped key: %w", err)
	}

	switch encAlg {
	case "RSA-OAEP-256+A256GCM":
		return rsa.DecryptOAEP(sha256.New(), rand.Reader, devicePriv, wrappedBytes, nil)
	case "RSA-OAEP+A256GCM":
		return rsa.DecryptOAEP(sha1.New(), rand.Reader, devicePriv, wrappedBytes, nil)
	default:
		return nil, fmt.Errorf("unsupported encAlgorithm: %s", encAlg)
	}
}

func decryptPayload(aesKey []byte, ivB64, ctB64, protectedB64 string) ([]byte, error) {
	iv, err := base64.RawURLEncoding.DecodeString(ivB64)
	if err != nil {
		return nil, fmt.Errorf("base64 decode iv: %w", err)
	}
	ctAndTag, err := base64.RawURLEncoding.DecodeString(ctB64)
	if err != nil {
		return nil, fmt.Errorf("base64 decode ct: %w", err)
	}

	block, err := aes.NewCipher(aesKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	aad := []byte("HKVPN-PROFILE-V2\n" + protectedB64)
	return gcm.Open(nil, iv, ctAndTag, aad)
}

func runEnvelopePipeline(
	env EnvelopeDoc,
	serverEC, rogueEC *ecdsa.PublicKey,
	devKey *rsa.PrivateKey,
	devInfo DeviceKeyDoc,
	lastObservedRevision int,
	now time.Time,
) (bool, string, []byte) {
	// Step 1: Signature verification
	code, err := verifyServerECDSASignature(serverEC, rogueEC, env)
	if err != nil {
		return false, code, nil
	}

	// Step 2: Metadata verification
	metaBytes, err := base64.RawURLEncoding.DecodeString(env.Protected)
	if err != nil {
		return false, "METADATA_DECODE_FAILED", nil
	}
	var meta ProtectedMetadata
	if err := json.Unmarshal(metaBytes, &meta); err != nil {
		return false, "METADATA_DECODE_FAILED", nil
	}

	if meta.Purpose != "profile" || meta.SchemaVersion != 2 || meta.SignatureAlgorithm != "ES256" {
		return false, "METADATA_DECODE_FAILED", nil
	}
	if meta.DeviceID != devInfo.DeviceID {
		return false, "CROSS_DEVICE_VIOLATION", nil
	}
	if meta.EncAlgorithm != devInfo.EncAlgorithm {
		return false, "METADATA_DECODE_FAILED", nil
	}

	expTime, err := time.Parse(time.RFC3339, meta.CredentialExpiresAt)
	if err != nil || !expTime.After(now) {
		return false, "CREDENTIAL_EXPIRED", nil
	}
	if meta.ProfileRevision < lastObservedRevision {
		return false, "ROLLBACK_DETECTED", nil
	}

	// Step 3: Unwrap AES key
	aesKey, err := unwrapAESKey(devKey, meta.EncAlgorithm, env.WrappedKey)
	if err != nil || len(aesKey) != 32 {
		return false, "KEY_UNWRAP_FAILED", nil
	}

	// Step 4: AES-GCM Decrypt
	decrypted, err := decryptPayload(aesKey, env.IV, env.Ciphertext, env.Protected)
	if err != nil {
		return false, "GCM_AUTH_FAILED", nil
	}

	return true, "OK", decrypted
}

func TestProfileEnvelopeGoldenVectors(t *testing.T) {
	data, err := os.ReadFile("../../../envelope-vectors.json")
	if err != nil {
		t.Fatalf("failed to read envelope-vectors.json: %v", err)
	}

	var doc EnvelopeVectorsDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("failed to parse envelope-vectors.json: %v", err)
	}

	serverEC, err := parseECPublicKey(doc.ServerKey.PublicKeyPem)
	if err != nil {
		t.Fatalf("failed to parse server EC key: %v", err)
	}
	rogueEC, err := parseECPublicKey(doc.RogueServerKey.PublicKeyPem)
	if err != nil {
		t.Fatalf("failed to parse rogue server EC key: %v", err)
	}

	devicePrivs := make(map[string]*rsa.PrivateKey)
	for name, d := range doc.DeviceKeys {
		pk, err := parseRSAPrivateKey(d.PrivateKeyPkcs8Pem)
		if err != nil {
			t.Fatalf("failed to parse device private key %s: %v", name, err)
		}
		devicePrivs[name] = pk
	}

	now := time.Date(2026, 10, 6, 21, 30, 0, 0, time.UTC)

	t.Run("PositiveVectors", func(t *testing.T) {
		for _, vec := range doc.PositiveVectors {
			vec := vec
			t.Run(vec.ID, func(t *testing.T) {
				devKey := devicePrivs[vec.TargetDeviceRef]
				devInfo := doc.DeviceKeys[vec.TargetDeviceRef]

				ok, reason, decrypted := runEnvelopePipeline(vec.Envelope, serverEC, rogueEC, devKey, devInfo, 1, now)
				if !ok {
					t.Fatalf("pipeline failed: %s", reason)
				}

				var decJSON, expectedJSON any
				_ = json.Unmarshal(decrypted, &decJSON)
				_ = json.Unmarshal(vec.Plaintext, &expectedJSON)
				decStr, _ := json.Marshal(decJSON)
				expStr, _ := json.Marshal(expectedJSON)
				if string(decStr) != string(expStr) {
					t.Fatalf("decrypted plaintext mismatch:\nGot:  %s\nWant: %s", decStr, expStr)
				}
			})
		}
	})

	t.Run("NegativeVectors", func(t *testing.T) {
		for _, vec := range doc.NegativeVectors {
			vec := vec
			t.Run(vec.ID, func(t *testing.T) {
				targetRef := vec.TargetDeviceRef
				if targetRef == "" {
					targetRef = "modern_device"
				}
				devKey := devicePrivs[targetRef]
				devInfo := doc.DeviceKeys[targetRef]

				lastRev := vec.LastObservedRevision
				if lastRev == 0 {
					lastRev = 1
				}

				ok, actualReason, _ := runEnvelopePipeline(vec.Envelope, serverEC, rogueEC, devKey, devInfo, lastRev, now)
				if ok {
					t.Fatalf("negative vector %s was unexpectedly accepted!", vec.ID)
				}
				if actualReason != vec.ExpectedError {
					t.Fatalf("vector %s: expected %s, got %s", vec.ID, vec.ExpectedError, actualReason)
				}
			})
		}
	})
}
