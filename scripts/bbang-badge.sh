#!/usr/bin/env bash

set -euo pipefail

base_url="${BBANG_API_BASE_URL:-http://localhost:8080}"
command_name="${1:-}"

usage() {
	printf '%s\n' \
		"Usage:" \
		"  $0 list" \
		"  $0 add '<json>'" \
		"  $0 update <badge-id> '<json>'" \
		"  $0 off <badge-id>"
}

case "$command_name" in
	list)
		curl --fail-with-body --silent --show-error \
			"${base_url}/api/admin/badges"
		;;
	add)
		payload="${2:-}"
		[[ -n "$payload" ]] || { usage; exit 1; }
		curl --fail-with-body --silent --show-error \
			-X POST \
			-H 'Content-Type: application/json' \
			-d "$payload" \
			"${base_url}/api/admin/badges"
		;;
	update)
		badge_id="${2:-}"
		payload="${3:-}"
		[[ "$badge_id" =~ ^[0-9]+$ && -n "$payload" ]] || { usage; exit 1; }
		curl --fail-with-body --silent --show-error \
			-X PATCH \
			-H 'Content-Type: application/json' \
			-d "$payload" \
			"${base_url}/api/admin/badges/${badge_id}"
		;;
	off)
		badge_id="${2:-}"
		[[ "$badge_id" =~ ^[0-9]+$ ]] || { usage; exit 1; }
		curl --fail-with-body --silent --show-error \
			-X DELETE \
			"${base_url}/api/admin/badges/${badge_id}"
		;;
	*)
		usage
		exit 1
		;;
esac

printf '\n'
