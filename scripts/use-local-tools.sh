#!/usr/bin/env bash

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export PATH="$ROOT_DIR/.tools/go/bin:$PATH"
export GOCACHE="$ROOT_DIR/.tools/go-cache"
export GOPATH="$ROOT_DIR/.tools/go-path"

go version
