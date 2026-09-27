package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"regexp"
)

type traceIDKey struct{}

var traceparentPattern = regexp.MustCompile(`^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$`)

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

func resolveTraceID(traceparent, requestID string) string {
	if m := traceparentPattern.FindStringSubmatch(traceparent); m != nil {
		return m[1]
	}
	if requestIDPattern.MatchString(requestID) {
		return requestID
	}
	return newTraceID()
}

func newTraceID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "unknown"
	}
	return hex.EncodeToString(b)
}

func withTraceID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, traceIDKey{}, id)
}

func traceIDFrom(ctx context.Context) string {
	id, _ := ctx.Value(traceIDKey{}).(string)
	return id
}
