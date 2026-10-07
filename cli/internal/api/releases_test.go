package api

import (
	"testing"
)

// The S9-06 release helpers (issue #60): quotable-id normalization,
// the PRD 17 vocabulary mirror, the promote-walk table, and the
// newest-READY-per-platform artifact selection.

func TestNormalizeReleaseRef(t *testing.T) {
	relID := "77777777-7777-7777-7777-777777777777"
	for in, want := range map[string]string{
		"R-1028": "R-1028",
		"r1028":  "R-1028",
		"R1028":  "R-1028",
		"1028":   "R-1028",
		" R-7 ":  "R-7",
		relID:    relID,
	} {
		if got := NormalizeReleaseRef(in); got != want {
			t.Errorf("NormalizeReleaseRef(%q) = %q, want %q", in, got, want)
		}
	}
	for _, bad := range []string{"", "R-", "R-x", "release-1", "R-1.5"} {
		if got := NormalizeReleaseRef(bad); got != "" {
			t.Errorf("NormalizeReleaseRef(%q) = %q, want empty", bad, got)
		}
	}
}

func TestReleaseStateVocabulary(t *testing.T) {
	for _, state := range []string{
		"DRAFT", "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED",
		"DEPLOYING", "ACTIVE", "PROMOTED", "COMPLETED", "ROLLED_BACK", "FAILED",
	} {
		if !ValidReleaseState(state) {
			t.Errorf("%s rejected by the vocabulary mirror", state)
		}
		if state != "PROMOTED" && !ReleaseTerminalState(state) && NextReleaseStateTowardPromoted(state) == "" {
			t.Errorf("%s has no forward path toward PROMOTED", state)
		}
	}
	for _, bad := range []string{"", "PROMOTE", "READY ", "approved"} {
		if ValidReleaseState(bad) {
			t.Errorf("%q accepted", bad)
		}
	}
	if NormalizeReleaseState(" approved ") != "APPROVED" {
		t.Error("state not normalized like the server does")
	}
	for terminal, want := range map[string]string{"PROMOTED": "", "COMPLETED": "", "ROLLED_BACK": "", "FAILED": ""} {
		if got := NextReleaseStateTowardPromoted(terminal); got != want {
			t.Errorf("terminal %s walks to %q", terminal, got)
		}
		if !ReleaseTerminalState(terminal) && terminal != "PROMOTED" {
			t.Errorf("%s not marked terminal", terminal)
		}
	}
	if !ReasonRequiredState("FAILED") || !ReasonRequiredState("ROLLED_BACK") || ReasonRequiredState("APPROVED") {
		t.Error("reason-required mirror wrong")
	}
	// The full path DRAFT -> PROMOTED is deterministic (PRD 17 offers
	// exactly one forward edge from every pre-PROMOTED state).
	state := "DRAFT"
	steps := 0
	for {
		next := NextReleaseStateTowardPromoted(state)
		if next == "" {
			break
		}
		state = next
		steps++
		if steps > 10 {
			t.Fatal("walk does not terminate")
		}
	}
	if state != "PROMOTED" || steps != 7 {
		t.Errorf("walk ended at %s in %d steps, want PROMOTED in 7", state, steps)
	}
}

func TestSelectReleaseArtifacts(t *testing.T) {
	version := &VersionView{ArtifactsByPlatform: map[string][]VersionArtifactSlot{
		"IOS": {
			{ID: "old-ready", Status: "READY", UploadedAt: "2026-10-06T09:00:00Z"},
			{ID: "new-ready", Status: "READY", UploadedAt: "2026-10-07T09:00:00Z"},
		},
		"ANDROID": {
			{ID: "failed", Status: "FAILED", UploadedAt: "2026-10-08T09:00:00Z"},
		},
	}}
	got := SelectReleaseArtifacts(version, nil)
	if len(got) != 1 || got[0].Platform != "IOS" || got[0].ArtifactID != "new-ready" {
		t.Errorf("selection = %+v, want only the newest READY IOS slot", got)
	}

	got = SelectReleaseArtifacts(version, map[string]string{"IOS": "explicit"})
	if len(got) != 1 || got[0].ArtifactID != "explicit" {
		t.Errorf("override ignored: %+v", got)
	}

	empty := &VersionView{ArtifactsByPlatform: map[string][]VersionArtifactSlot{
		"ANDROID": {{ID: "failed", Status: "FAILED", UploadedAt: "2026-10-08T09:00:00Z"}},
	}}
	if got := SelectReleaseArtifacts(empty, nil); len(got) != 0 {
		t.Errorf("a version with no READY artifacts selected %+v", got)
	}

	// Equal timestamps fall back to the deterministic id tiebreak.
	tie := &VersionView{ArtifactsByPlatform: map[string][]VersionArtifactSlot{
		"IOS": {
			{ID: "a", Status: "READY", UploadedAt: "2026-10-07T09:00:00Z"},
			{ID: "b", Status: "READY", UploadedAt: "2026-10-07T09:00:00Z"},
		},
	}}
	if got := SelectReleaseArtifacts(tie, nil); len(got) != 1 || got[0].ArtifactID != "b" {
		t.Errorf("tiebreak not deterministic: %+v", got)
	}
}
