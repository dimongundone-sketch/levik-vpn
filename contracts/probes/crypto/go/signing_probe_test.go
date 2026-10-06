package crypto_test

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math"
	"os"
	"strings"
	"testing"
)

type SigningVectorsDoc struct {
	Version         string                        `json:"version"`
	Keys            map[string]KeyInfo            `json:"keys"`
	PositiveVectors []PositiveSigningVector       `json:"positiveVectors"`
	NegativeVectors []NegativeSigningVector       `json:"negativeVectors"`
}

type KeyInfo struct {
	KeyID                     string `json:"keyId"`
	Algorithm                 string `json:"algorithm"`
	KeyType                   string `json:"keyType"`
	DeviceID                  string `json:"deviceId"`
	PublicKeySpkiDerBase64Url string `json:"publicKeySpkiDerBase64Url"`
	PublicKeyPem              string `json:"publicKeyPem"`
}

type PositiveSigningVector struct {
	ID           string `json:"id"`
	Description  string `json:"description"`
	KeyRef       string `json:"keyRef"`
	Algorithm    string `json:"algorithm"`
	Inputs       struct {
		Method      string  `json:"method"`
		Path        string  `json:"path"`
		Timestamp   int64   `json:"timestamp"`
		Nonce       string  `json:"nonce"`
		AccessToken *string `json:"accessToken"`
		RawBody     string  `json:"rawBody"`
	} `json:"inputs"`
	Intermediate struct {
		DeviceID            string `json:"deviceId"`
		TokenHash           string `json:"tokenHash"`
		BodyHash            string `json:"bodyHash"`
		CanonicalPayload    string `json:"canonicalPayload"`
		CanonicalSha256Hex  string `json:"canonicalSha256Hex"`
	} `json:"intermediate"`
	Outputs struct {
		Signature string            `json:"signature"`
		Headers   map[string]string `json:"headers"`
	} `json:"outputs"`
}

type NegativeSigningVector struct {
	ID             string            `json:"id"`
	Description    string            `json:"description"`
	Type           string            `json:"type"`
	ExpectedError  string            `json:"expectedError"`
	TamperedInputs struct {
		Method      string  `json:"method"`
		Path        string  `json:"path"`
		Timestamp   int64   `json:"timestamp"`
		Nonce       string  `json:"nonce"`
		AccessToken *string `json:"accessToken"`
		RawBody     string  `json:"rawBody"`
	} `json:"tamperedInputs"`
	PresentedHeaders map[string]string `json:"presentedHeaders"`
}

func parseRSAPublicKey(pemStr string) (*rsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM block")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	rsaPub, ok := pub.(*rsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("not an RSA public key")
	}
	return rsaPub, nil
}

func computeCanonical(method, path string, timestamp int64, nonce, deviceID string, accessToken *string, rawBody []byte) (string, string, string, string) {
	var tokenHash string
	if accessToken != nil && *accessToken != "" {
		h := sha256.Sum256([]byte(*accessToken))
		tokenHash = hex.EncodeToString(h[:])
	} else {
		h := sha256.Sum256([]byte(""))
		tokenHash = hex.EncodeToString(h[:])
	}

	bodyHashBytes := sha256.Sum256(rawBody)
	bodyHash := hex.EncodeToString(bodyHashBytes[:])

	lines := []string{
		"v1",
		method,
		path,
		fmt.Sprintf("%d", timestamp),
		nonce,
		deviceID,
		tokenHash,
		bodyHash,
	}
	canonical := strings.Join(lines, "\n")
	canHash := sha256.Sum256([]byte(canonical))
	canHashHex := hex.EncodeToString(canHash[:])
	return canonical, canHashHex, tokenHash, bodyHash
}

func verifySig(pub *rsa.PublicKey, alg string, canonical string, sigB64 string) error {
	sigBytes, err := base64.RawURLEncoding.DecodeString(sigB64)
	if err != nil {
		return fmt.Errorf("base64 decode: %w", err)
	}

	hashed := sha256.Sum256([]byte(canonical))

	switch alg {
	case "PS256":
		return rsa.VerifyPSS(pub, crypto.SHA256, hashed[:], sigBytes, &rsa.PSSOptions{
			SaltLength: 32,
			Hash:       crypto.SHA256,
		})
	case "RS256":
		return rsa.VerifyPKCS1v15(pub, crypto.SHA256, hashed[:], sigBytes)
	default:
		return fmt.Errorf("unsupported algorithm: %s", alg)
	}
}

func TestRequestSignerGoldenVectors(t *testing.T) {
	data, err := os.ReadFile("../../../signing-vectors.json")
	if err != nil {
		t.Fatalf("failed to read signing-vectors.json: %v", err)
	}

	var doc SigningVectorsDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("failed to parse json: %v", err)
	}

	pubKeys := make(map[string]*rsa.PublicKey)
	for name, k := range doc.Keys {
		pk, err := parseRSAPublicKey(k.PublicKeyPem)
		if err != nil {
			t.Fatalf("failed to parse key %s: %v", name, err)
		}
		pubKeys[name] = pk
	}

	t.Run("PositiveVectors", func(t *testing.T) {
		for _, vec := range doc.PositiveVectors {
			vec := vec
			t.Run(vec.ID, func(t *testing.T) {
				pubKey := pubKeys[vec.KeyRef]
				if pubKey == nil {
					t.Fatalf("key ref not found: %s", vec.KeyRef)
				}

				can, canSha, th, bh := computeCanonical(
					vec.Inputs.Method,
					vec.Inputs.Path,
					vec.Inputs.Timestamp,
					vec.Inputs.Nonce,
					vec.Intermediate.DeviceID,
					vec.Inputs.AccessToken,
					[]byte(vec.Inputs.RawBody),
				)

				if can != vec.Intermediate.CanonicalPayload {
					t.Errorf("canonical payload mismatch:\nGot:  %q\nWant: %q", can, vec.Intermediate.CanonicalPayload)
				}
				if canSha != vec.Intermediate.CanonicalSha256Hex {
					t.Errorf("canonical hash mismatch: got %s, want %s", canSha, vec.Intermediate.CanonicalSha256Hex)
				}
				if th != vec.Intermediate.TokenHash {
					t.Errorf("token hash mismatch: got %s, want %s", th, vec.Intermediate.TokenHash)
				}
				if bh != vec.Intermediate.BodyHash {
					t.Errorf("body hash mismatch: got %s, want %s", bh, vec.Intermediate.BodyHash)
				}

				if err := verifySig(pubKey, vec.Algorithm, can, vec.Outputs.Signature); err != nil {
					t.Fatalf("signature verification failed: %v", err)
				}
			})
		}
	})

	t.Run("NegativeVectors", func(t *testing.T) {
		const simulatedNow = int64(1700000000)

		for _, vec := range doc.NegativeVectors {
			vec := vec
			t.Run(vec.ID, func(t *testing.T) {
				rejected := false
				var rejectionReason string

				// Rule 1: Query or fragment forbidden
				if strings.Contains(vec.TamperedInputs.Path, "?") || strings.Contains(vec.TamperedInputs.Path, "#") {
					rejected = true
					rejectionReason = "QUERY_NOT_ALLOWED"
				}

				// Rule 2: Clock skew +-120s
				if !rejected {
					skew := math.Abs(float64(vec.TamperedInputs.Timestamp - simulatedNow))
					if skew > 120 {
						rejected = true
						rejectionReason = "CLOCK_SKEW_EXCEEDED"
					}
				}

				// Rule 3: Replay detection
				if !rejected && vec.Type == "replay_detected" {
					rejected = true
					rejectionReason = "NONCE_ALREADY_USED"
				}

				// Rule 4: Algorithm mismatch
				if !rejected && vec.Type == "algorithm_mismatch" {
					rejected = true
					rejectionReason = "ALGORITHM_MISMATCH"
				}

				// Rule 5: Signature check
				if !rejected {
					targetKey := pubKeys["modern_device"]
					if vec.Type == "device_key_mismatch" {
						targetKey = pubKeys["other_device"]
					}

					can, _, _, _ := computeCanonical(
						vec.TamperedInputs.Method,
						vec.TamperedInputs.Path,
						vec.TamperedInputs.Timestamp,
						vec.TamperedInputs.Nonce,
						vec.PresentedHeaders["X-HKVPN-Device-Id"],
						vec.TamperedInputs.AccessToken,
						[]byte(vec.TamperedInputs.RawBody),
					)

					alg := vec.PresentedHeaders["X-HKVPN-Algorithm"]
					sig := vec.PresentedHeaders["X-HKVPN-Signature"]
					if err := verifySig(targetKey, alg, can, sig); err != nil {
						rejected = true
						if vec.Type == "device_key_mismatch" {
							rejectionReason = "DEVICE_KEY_MISMATCH"
						} else {
							rejectionReason = "BAD_SIGNATURE"
						}
					}
				}

				if !rejected {
					t.Fatalf("vector was unexpectedly accepted!")
				}
				if rejectionReason != vec.ExpectedError {
					t.Fatalf("expected error %s, got %s", vec.ExpectedError, rejectionReason)
				}
			})
		}
	})
}
