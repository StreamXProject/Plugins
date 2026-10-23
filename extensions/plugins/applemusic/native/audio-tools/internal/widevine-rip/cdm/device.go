package wv

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"sync"
)

// Device is the identity a CDM presents to a license server: the device RSA
// private key and its serialized ClientIdentification blob.
type Device struct {
	PrivateKey *rsa.PrivateKey
	ClientID   []byte
}

// NewDevice parses a PKCS#1 PEM private key and a serialized
// ClientIdentification blob into a Device.
func NewDevice(privateKeyPEM []byte, clientID []byte) (*Device, error) {
	block, _ := pem.Decode(privateKeyPEM)
	if block == nil || block.Type != "RSA PRIVATE KEY" {
		return nil, errors.New("failed to decode device private key")
	}
	privateKey, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse device private key: %w", err)
	}
	if len(clientID) == 0 {
		return nil, errors.New("empty device client ID")
	}
	return &Device{PrivateKey: privateKey, ClientID: clientID}, nil
}

// LoadDevice reads a device from a PEM private key file and a raw client ID
// blob file (the usual device_private_key / device_client_id_blob pair).
func LoadDevice(privateKeyPath string, clientIDPath string) (*Device, error) {
	privateKeyPEM, err := os.ReadFile(privateKeyPath)
	if err != nil {
		return nil, fmt.Errorf("read device private key: %w", err)
	}
	clientID, err := os.ReadFile(clientIDPath)
	if err != nil {
		return nil, fmt.Errorf("read device client ID: %w", err)
	}
	return NewDevice(privateKeyPEM, clientID)
}

// DefaultDevice loads an externally provisioned device identity. Device private
// keys and client identity blobs must never be distributed in source repositories.
var DefaultDevice = sync.OnceValues(func() (*Device, error) {
	key := os.Getenv("AMDL_DEVICE_PRIVATE_KEY_FILE")
	client := os.Getenv("AMDL_DEVICE_CLIENT_ID_FILE")
	if key == "" || client == "" {
		return nil, errors.New("configure AMDL_DEVICE_PRIVATE_KEY_FILE and AMDL_DEVICE_CLIENT_ID_FILE for device-based playback")
	}
	return LoadDevice(key, client)
})
