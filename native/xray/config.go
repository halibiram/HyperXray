package xray

import "encoding/json"

// Config represents Xray configuration
type Config struct {
	ServerAddress string `json:"serverAddress"`
	ServerPort    int    `json:"serverPort"`
	UUID          string `json:"uuid"`
	Flow          string `json:"flow,omitempty"`
	Security      string `json:"security"`
	SNI           string `json:"sni,omitempty"`
	Fingerprint   string `json:"fingerprint,omitempty"`
	PublicKey     string `json:"publicKey,omitempty"`
	ShortID       string `json:"shortId,omitempty"`
}

// ParseConfig parses JSON config string into Config struct
func ParseConfig(configJSON string) (*Config, error) {
	var config Config
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return nil, err
	}
	return &config, nil
}
