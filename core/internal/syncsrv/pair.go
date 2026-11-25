package syncsrv

import (
	"encoding/json"
	"errors"
	"net/http"

	"styli/core/internal/pairing"
)

// The pairing endpoints. They are the only part of the sync port a device
// that is not paired can reach, and only while the atrium has a pairing open
// (see pairing.Atrium.Admits).
func (s *server) pairRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /pair/v1/start", s.pairStart)
	mux.HandleFunc("POST /pair/v1/confirm", s.pairConfirm)
	mux.HandleFunc("POST /pair/v1/approve", s.pairApprove)
	mux.HandleFunc("POST /pair/v1/cancel", s.pairCancel)
	mux.HandleFunc("GET /pair/v1/status", s.pairStatus)
}

func (s *server) pairStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		MsgA []byte `json:"msg_a"`
		Name string `json:"name"`
	}
	if !readSmall(w, r, &req) {
		return
	}
	exporter, err := r.TLS.ExportKeyingMaterial(pairing.ExporterLabel, nil, pairing.ExporterLen)
	if err != nil {
		s.pairFail(w, err)
		return
	}
	msgB, proof, err := s.Pairing.Start(DeviceID(r.Context()), req.Name, exporter, req.MsgA)
	if err != nil {
		s.pairFail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"msg_b": msgB, "proof": proof})
}

func (s *server) pairConfirm(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Proof []byte `json:"proof"`
	}
	if !readSmall(w, r, &req) {
		return
	}
	emoji, err := s.Pairing.Confirm(DeviceID(r.Context()), req.Proof)
	if err != nil {
		s.pairFail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"emoji": emoji})
}

func (s *server) pairApprove(w http.ResponseWriter, r *http.Request) {
	st, err := s.Pairing.Approve(DeviceID(r.Context()))
	if err != nil {
		s.pairFail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *server) pairCancel(w http.ResponseWriter, r *http.Request) {
	s.Pairing.Cancel(DeviceID(r.Context()))
	writeJSON(w, http.StatusOK, map[string]any{})
}

func (s *server) pairStatus(w http.ResponseWriter, r *http.Request) {
	st, err := s.Pairing.DeviceStatus(DeviceID(r.Context()))
	if err != nil {
		s.pairFail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *server) pairFail(w http.ResponseWriter, err error) {
	code := pairing.ErrorCode(err)
	switch {
	case code == "":
		s.Logf("sync: pairing: %v", err)
		fail(w, http.StatusInternalServerError, "internal", "something went wrong on the atrium")
	case errors.Is(err, pairing.ErrBadName):
		fail(w, http.StatusBadRequest, code, err.Error())
	default:
		fail(w, http.StatusForbidden, code, err.Error())
	}
}

// readSmall reads a small JSON body (pairing messages are tiny).
func readSmall(w http.ResponseWriter, r *http.Request, dst any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		fail(w, http.StatusBadRequest, "bad_request", "not a valid pairing message")
		return false
	}
	return true
}
