# Mistakes ledger

Named, categorized mistakes awaiting promotion to a durable memory surface. Maintained by the mistake-cartographer skill. One section per Pattern-Key, newest first.

## [MC-20260731-013] tool-env/delivered-command-assumes-cwd
- Pattern-Key: tool-env/delivered-command-assumes-cwd
- Family: Tool/Environment
- Mode: missing-precondition
- Logged: 2026-07-31T21:18:53Z
- Recurrence: 2
- Status: pending
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: Handed over `git checkout main; git pull` and later `git branch -d ...` as paste-ready chunks; both died on fatal: not a git repository because the user's prompt sat at C:\Users\shave, not the repo. The gh command in the same set already carried --repo owner/name to make it directory-independent, so the fix was known and simply not applied to the git commands beside it. The second failure came after the first had been acknowledged.
- Root-Cause: Authored the command from the assistant's own fixed working directory and assumed the user's shell shares it. A delivered chunk carries no context but its own text.
- Generalized-Rule: Every command handed to the user to run must establish its own working directory or name its target explicitly. Lead a location-dependent chunk with cd to an absolute path, or use the tool's own scoping flag (git -C PATH, gh --repo OWNER/NAME). A chunk that only works from one directory is not paste-ready.
- Scope-Test: Fires on every delivered command that touches a specific location; no counter-case, because an absolute cd or -C flag is harmless when the shell is already in the right place; durable
- Contributing: none
- Promoted-To: none
- See-Also: MC-20260708-003 tool-env/ps-paste-no-abort, MC-20260708-002 verification/suppressed-diagnostics

## [MC-20260715-012] tool-env/blocking-wait-kills-workflow
- Pattern-Key: tool-env/blocking-wait-kills-workflow
- Family: Tool/Environment
- Mode: wrong-tool
- Logged: 2026-07-15T14:15:00Z
- Recurrence: 1
- Status: pending
- Scope: all-work
- Surface: ledger
- Trigger-Context: Launched a 3-agent "board meeting" workflow (each agent reading a full skill corpus + repo), then held a blocking TaskOutput wait; the user's only channel to redirect was an interrupt, which killed the workflow after ~7 minutes with zero completed agents and nothing salvageable in the journal.
- Root-Cause: Blocking on a long background task is the wrong tool use — the harness resumes on completion anyway, and a blocking wait converts any user interjection into a kill of all in-flight agents. Contributing: fan-out agents oversized, so none finished inside the user's patience window.
- Generalized-Rule: After launching a background workflow expected to run longer than ~2 minutes, end the turn and let the completion notification resume the work; never hold a blocking wait on it. Size fan-out agents so each completes in ~2-3 minutes (split reading-heavy briefs).
- Scope-Test: Fires on every long background launch (frequent); bounded by the ~2-minute threshold so quick builds/tests can still be awaited inline — no false positives on short waits.
- Contributing: reasoning/oversized-fanout-agents
- Promoted-To: none
- See-Also: none

## [MC-20260708-011] convention/self-exec-vs-chunk
- Pattern-Key: convention/self-exec-vs-chunk
- Family: Convention/Preference
- Mode: stated-preference-violated
- Logged: 2026-07-08T21:51:08Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: Drove the desktop through six round trips to start the server when a one-paste PowerShell chunk was the preferred path; user confirmed chunk-first as a standing preference.
- Root-Cause: Read start it yourself literally instead of weighing it against the standing PowerShell delivery preference and the cost of desktop control.
- Generalized-Rule: When a task can run on the user's machine, offer the paste-ready PowerShell chunk first. Reserve desktop control for what a chunk cannot do or an explicit hands-off request.
- Scope-Test: User stated the preference explicitly, durable by definition, promote now
- Contributing: none
- Promoted-To: userPreferences
- See-Also: none

## [MC-20260708-010] tool-env/design-roundtrip-mockup
- Pattern-Key: tool-env/design-roundtrip-mockup
- Family: Tool/Environment
- Mode: wrong-tool
- Logged: 2026-07-08T20:36:45Z
- Recurrence: 1
- Status: pending
- Scope: all-work
- Surface: ledger
- Trigger-Context: Claude design round trip returned a nonfunctional React mockup bundle instead of the requested drop-in index.html revision, despite explicit contract constraints in the brief.
- Root-Cause: Design tools rebuild interfaces as component prototypes; they do not merge into existing working code regardless of prompt instructions.
- Generalized-Rule: Treat design-tool output as a visual reference, never a drop-in. Extract its tokens and deltas, diff against the working file, and graft only verified changes.
- Scope-Test: Single occurrence, likely durable tool behavior, hold for recurrence before promoting
- Contributing: none
- Promoted-To: none
- See-Also: none

## [MC-20260708-009] tool-env/imported-memory-readonly
- Pattern-Key: tool-env/imported-memory-readonly
- Family: Tool/Environment
- Mode: missing-precondition
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: pending
- Scope: all-work
- Surface: ledger
- Trigger-Context: Attempted to write memory.md inside the imported claude.ai project cache; the mount is read-only in Cowork.
- Root-Cause: Imported project knowledge is a read-only cache; durable memory must route to a writable surface.
- Generalized-Rule: In Cowork, never write to imported project caches. Route session memory to a project state file in a connected folder and hand the user a line for claude.ai project memory.
- Scope-Test: Platform behavior, durable, low cost, candidate to fold into the Cowork platform rules on promotion
- Contributing: none
- Promoted-To: none
- See-Also: none

## [MC-20260708-008] reasoning/fanout-token-underestimate
- Pattern-Key: reasoning/fanout-token-underestimate
- Family: Reasoning/Logic
- Mode: false-assumption
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: pending
- Scope: all-work
- Surface: ledger
- Trigger-Context: Quoted 500 to 800k tokens for the full build; actuals ran about 1.64M across subagents, 2x the top of range.
- Root-Cause: Estimated research fan-out at typical single-agent usage without a calibration margin for deep verification passes.
- Generalized-Rule: For multi-agent fan-outs, quote token estimates with a 2x tail and notify when actuals cross the top of the quoted range.
- Scope-Test: Single data point, keep in ledger until a second fan-out confirms the multiplier
- Contributing: none
- Promoted-To: none
- See-Also: none

## [MC-20260708-007] convention/native-dialog-no-walkthrough
- Pattern-Key: convention/native-dialog-no-walkthrough
- Family: Convention/Preference
- Mode: workflow-skip
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: pending
- Scope: all-work
- Surface: ledger
- Trigger-Context: Fired the native folder picker without click-by-click instructions first; user closed it saying instructions unclear.
- Root-Cause: A dialog appearing on the user's screen with no walkthrough forces them to guess the intent.
- Generalized-Rule: Before triggering any native dialog on the user's screen, state in the same message what will appear and the exact clicks to complete it.
- Scope-Test: General and cheap, but single occurrence, hold for recurrence
- Contributing: none
- Promoted-To: none
- See-Also: none

## [MC-20260708-006] knowledge/up-axis-unstated
- Pattern-Key: knowledge/up-axis-unstated
- Family: Knowledge/Factual
- Mode: wrong-version
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: this-project
- Surface: PROJECT_STATE.md
- Trigger-Context: Viewer applied the COLMAP flip to the Y-up demo scene, which would have rendered it upside down.
- Root-Cause: The 3D asset contract between generator and viewer never stated up axis, so each side assumed its own convention.
- Generalized-Rule: Every 3D asset handoff states its up axis and coordinate convention explicitly. Pipeline artifacts are Y-down COLMAP and get the 180 degree X flip. Generated or web-native assets are Y-up and never flipped.
- Scope-Test: Holds for all splat and mesh handoffs in this project, durable convention
- Contributing: none
- Promoted-To: orbit-studio/PROJECT_STATE.md
- See-Also: none

## [MC-20260708-005] verification/parallel-seam-drift
- Pattern-Key: verification/parallel-seam-drift
- Family: Verification/Process
- Mode: skipped-gate
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: efficient-fable SKILL.md
- Trigger-Context: Parallel build agents drifted at seams: UI linked routes the server lacked, a stage required a dataset no stage produces, bundle manifest keys mismatched their reader. Caught in the crosswalk loop before shipping.
- Root-Cause: Independent agents each satisfy their own contract reading; nobody owns the seam unless the orchestrator diffs both sides.
- Generalized-Rule: After parallel build slices return, run an interface crosswalk before shipping: diff every producer-consumer seam and trace one end-to-end path through the integrated system.
- Scope-Test: Applies to every multi-agent build, no counter-case, coordination behavior is durable
- Contributing: none
- Promoted-To: efficient-fable SKILL.md
- See-Also: none

## [MC-20260708-004] tool-env/cross-mount-desync
- Pattern-Key: tool-env/cross-mount-desync
- Family: Tool/Environment
- Mode: env-mismatch
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: Sandbox mirror of index.html lagged truncated behind the host copy; the tar copy shipped the truncated version and needed a verified tail repair.
- Root-Cause: The same folder seen through two layers was assumed identical; the mirror can lag mid-write.
- Generalized-Rule: After any cross-layer file copy, verify byte integrity at the destination through the layer the consumer will use: size, tail, and a full parse.
- Scope-Test: Fires on every cross-layer copy in Cowork, verification cost is trivial, cause is durable
- Contributing: Verification/Process
- Promoted-To: userPreferences
- See-Also: none

## [MC-20260708-003] tool-env/ps-paste-no-abort
- Pattern-Key: tool-env/ps-paste-no-abort
- Family: Tool/Environment
- Mode: env-mismatch
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: return after Copy failed did not stop later pasted lines, so the console cascaded four follow-on errors.
- Root-Cause: Interactive PowerShell executes pasted lines independently, so abort guards only work inside a single scriptblock.
- Generalized-Rule: Wrap every paste-ready PowerShell chunk in an ampersand scriptblock so early return guards abort the whole block.
- Scope-Test: Holds for all paste chunks, no counter-case, PowerShell semantics are durable
- Contributing: none
- Promoted-To: userPreferences
- See-Also: none

## [MC-20260708-002] verification/suppressed-diagnostics
- Pattern-Key: verification/suppressed-diagnostics
- Family: Verification/Process
- Mode: unverified-output
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: First install chunk piped robocopy to Out-Null, so the real error was invisible and a second diagnostic round trip was needed.
- Root-Cause: Suppressing tool output in a user-run script removes the only observability channel when it fails on their machine.
- Generalized-Rule: In delivered scripts, never suppress output of the critical step. Print exit codes and errors so a failed run is self-diagnosing on the first paste.
- Scope-Test: Applies to any delivered script, cosmetic quieting of noncritical steps still allowed, durable
- Contributing: none
- Promoted-To: userPreferences
- See-Also: none

## [MC-20260708-001] tool-env/virtualized-session-path
- Pattern-Key: tool-env/virtualized-session-path
- Family: Tool/Environment
- Mode: wrong-path-or-target
- Logged: 2026-07-08T16:47:24Z
- Recurrence: 1
- Status: promoted
- Scope: all-work
- Surface: userPreferences
- Trigger-Context: Gave the user a robocopy chunk sourcing the Cowork AppData outputs path; that path is app-virtualized and does not exist on disk, so the install failed.
- Root-Cause: Assumed the harness file path equals the host filesystem path without verifying host-side visibility.
- Generalized-Rule: Treat Cowork session outputs paths as app-virtualized. Never hand them to user-run commands or Explorer. Deliver files by connecting a real folder and writing there, then verify host-side.
- Scope-Test: Fires on every Cowork delivery, no legitimate counter-case, platform behavior is durable
- Contributing: none
- Promoted-To: userPreferences
- See-Also: none
