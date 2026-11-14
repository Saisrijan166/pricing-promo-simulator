#!/usr/bin/env bash
# Draft PROMPT_HISTORY entries from Claude Code session transcripts (PLAN.md §15).
# Prints every human prompt (timestamp, session, verbatim text) as Markdown, oldest first. The output is a draft to
# copy into PROMPT_HISTORY.md and complete by hand ("What AI produced / What I did with it / Verification").
#
#   ./scripts/extract_prompt_history.sh [transcript-dir]   (default ~/.claude/projects/<this repo's path, dashed>)
#
# Removed, because they are not part of the prompt: IDE context notes (<ide_opened_file>), paste wrappers
# (<pasted_content>), tool results, and harness-generated messages (meta, command output, system reminders).
# Requires jq.
set -euo pipefail

command -v jq > /dev/null || { echo "jq is required" >&2; exit 1; }
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-${HOME}/.claude/projects/$(printf '%s' "${REPO}" | sed 's|[/.]|-|g')}"
[ -d "${DIR}" ] || { echo "No transcripts in ${DIR}" >&2; exit 1; }

cat "${DIR}"/*.jsonl | jq -rs '
  [ .[]
    | select(.type == "user" and (.isMeta | not) and (.isSidechain | not))
    | {ts: .timestamp, session: (.sessionId // "?"),
       text: (if (.message.content | type) == "string" then .message.content
              else ([.message.content[]? | select(.type == "text") | .text] | join("\n")) end)}
    | .text |= (gsub("<ide_opened_file>[^<]*</ide_opened_file>"; "")
               | gsub("</?pasted_content[^>]*>"; "")
               | gsub("^\\s+|\\s+$"; ""))
    | select(.text != ""
             and (.text | test("^<(command-|local-command|system-reminder|bash-|task-notification)") | not)
             and (.text | startswith("This session is being continued") | not)
             and (.text | startswith("[Request interrupted") | not)) ]
  | sort_by(.ts)
  | to_entries[]
  | "### #\(.key + 1 | tostring | if length < 2 then "0" + . else . end) — \(.value.ts[0:10]) \(.value.ts[11:16]) UTC — Claude Code — session \(.value.session[0:8])\n\n**Prompt (verbatim):**\n\n```text\n\(.value.text)\n```\n\n**What AI produced:** TODO\n\n**What I did with it:** TODO\n\n**Verification:** TODO\n\n---\n"'
