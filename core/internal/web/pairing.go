package web

import (
	"net/http"
)

// Pairing a new device, from the atrium's Settings screen. The device side
// talks to the sync port (see the pairing and syncsrv packages); these let the
// person start a pairing, see the words and then the emoji, and approve.

func (s *server) pairingOff(w http.ResponseWriter) bool {
	if s.Pairing == nil {
		fail(w, http.StatusNotFound, "sync_off", "Sync is off on this atrium, so devices can't pair. Start it with a sync address.")
		return true
	}
	return false
}

func (s *server) getPairing(w http.ResponseWriter, r *http.Request) {
	if s.pairingOff(w) {
		return
	}
	writeJSON(w, http.StatusOK, s.Pairing.Status())
}

func (s *server) openPairing(w http.ResponseWriter, r *http.Request) {
	if s.pairingOff(w) {
		return
	}
	writeJSON(w, http.StatusOK, s.Pairing.Open())
}

func (s *server) approvePairing(w http.ResponseWriter, r *http.Request) {
	if s.pairingOff(w) {
		return
	}
	st, err := s.Pairing.Approve("")
	if err != nil {
		s.Logf("web: pairing approve: %v", err)
	}
	writeJSON(w, http.StatusOK, st) // the status says what happened
}

func (s *server) cancelPairing(w http.ResponseWriter, r *http.Request) {
	if s.pairingOff(w) {
		return
	}
	s.Pairing.Cancel("")
	writeJSON(w, http.StatusOK, s.Pairing.Status())
}
