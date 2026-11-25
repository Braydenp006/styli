package web

import (
	"errors"
	"net/http"

	"styli/core/internal/devices"
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

// The device list on Settings: who is paired, and revoking or restoring one.
// Revoking cuts the device off at once (see devices and syncsrv); restoring
// only lifts that, it never re-pairs anything.

func (s *server) devicesOff(w http.ResponseWriter) bool {
	if s.Devices == nil {
		fail(w, http.StatusNotFound, "sync_off", "Sync is off on this atrium, so there are no devices.")
		return true
	}
	return false
}

func (s *server) listDevices(w http.ResponseWriter, r *http.Request) {
	if s.devicesOff(w) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": s.Devices.List()})
}

func (s *server) changeDevice(w http.ResponseWriter, r *http.Request, do func(string) error) {
	if s.devicesOff(w) {
		return
	}
	switch err := do(r.PathValue("id")); {
	case errors.Is(err, devices.ErrNotFound):
		fail(w, http.StatusNotFound, "not_found", "That device isn't paired with this atrium.")
	case err != nil:
		s.Logf("web: device change: %v", err)
		fail(w, http.StatusInternalServerError, "internal", "Couldn't save the device list. Nothing was changed.")
	default:
		writeJSON(w, http.StatusOK, map[string]any{"devices": s.Devices.List()})
	}
}

func (s *server) revokeDevice(w http.ResponseWriter, r *http.Request) {
	s.changeDevice(w, r, s.Devices.Revoke)
}

func (s *server) unrevokeDevice(w http.ResponseWriter, r *http.Request) {
	s.changeDevice(w, r, s.Devices.Unrevoke)
}

// syncStatus is how this device's sync with its atrium is going (404 on an
// atrium, which syncs nothing itself).
func (s *server) syncStatus(w http.ResponseWriter, r *http.Request) {
	if s.Sync == nil {
		fail(w, http.StatusNotFound, "not_device", "This is the atrium; it doesn't sync with another one.")
		return
	}
	writeJSON(w, http.StatusOK, s.Sync.Status())
}
