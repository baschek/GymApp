# Historical Workout CSV Transcription Standard

This document is a transcription contract for a human or another AI converting old workout records into a CSV that GymApp can import.

## Required Output

Produce one UTF-8 comma-separated `.csv` file. Each row represents exactly one set. A file may contain any number of workout sessions.

Output the CSV only when performing the transcription. Do not add Markdown fences, commentary, totals, or explanatory text around it.

Use this header exactly:

```csv
format_version,session_id,date,start_time,gym,workout_name,duration_minutes,exercise_order,exercise_id,exercise_name,set_number,set_type,status,weight_kg,reps,seconds,meters,rir,rest_seconds,set_duration_seconds,exercise_notes,session_notes
```

## Transcription Rules

1. Never invent a weight, repetition count, date, RIR, rest duration, or exercise identity.
2. Leave an optional field empty when the source does not contain it.
3. Ask the user for a missing date because every imported session requires an exact date.
4. Preserve the source exercise name in `exercise_name`.
5. Add `exercise_id` only when the user supplies an exact ID from their **My exercises** list. Otherwise leave it empty; GymApp provides a mapping screen.
6. Keep all sets from one workout under the same stable `session_id`.
7. Repeat the session metadata on every row belonging to that session.
8. Use a decimal point, not a decimal comma. Write `62.5`, not `62,5`.
9. Quote any field containing commas, quotes, or line breaks according to normal CSV rules.
10. Do not calculate missing RIR from subjective comments such as "hard" unless the user explicitly defines that conversion.
11. Preserve useful free text in `exercise_notes` or `session_notes`.
12. Sort sessions by date and exercises and sets by their original order.

## Column Reference

| Column | Required | Accepted value |
| --- | --- | --- |
| `format_version` | Yes | Always `1` |
| `session_id` | Recommended | Stable unique text, such as `2026-06-14-push-evening` |
| `date` | Yes | `YYYY-MM-DD` |
| `start_time` | No | Local time as `HH:MM` or `HH:MM:SS` |
| `gym` | Yes | `Basic Fit`, `John Reed`, `AI Fitness Lahnstein`, or `AI Fitness Koblenz` |
| `workout_name` | Yes | Human-readable session name |
| `duration_minutes` | No | Total session duration, repeated on every row |
| `exercise_order` | Yes | Positive integer; same number for all sets of one exercise |
| `exercise_id` | No | Exact ID from the user's **My exercises** list; normally blank |
| `exercise_name` | Yes | Exercise name as written or confidently normalized |
| `set_number` | Yes | Positive integer within the exercise |
| `set_type` | No | `working` or `warmup`; blank means `working` |
| `status` | No | `completed`, `failed`, `pain`, `skipped_time`, `skipped_equipment`, or `skipped_other`; blank means `completed` |
| `weight_kg` | No | Nonnegative kilograms |
| `reps` | No | Nonnegative repetition count |
| `seconds` | No | Recorded exercise duration |
| `meters` | No | Recorded exercise distance |
| `rir` | No | Numeric repetitions in reserve from `0` through `20` |
| `rest_seconds` | No | Actual rest immediately before this set; normally blank for the first set |
| `set_duration_seconds` | No | Actual elapsed duration of the set |
| `exercise_notes` | No | Setup, technique, machine, or exercise-specific note |
| `session_notes` | No | Note applying to the whole workout |

At least one of `weight_kg`, `reps`, `seconds`, or `meters` is required when `status` is `completed`.

## Session And Exercise Grouping

Rows with the same `session_id` become one workout. If `session_id` is blank, GymApp groups by date, start time, gym, and workout name. Supplying `session_id` is safer and enables duplicate detection.

Within a session:

- `exercise_order` identifies the exercise position.
- `set_number` identifies the set within that exercise.
- The pair of `exercise_order` and `set_number` must be unique.
- Date, start time, gym, workout name, and duration must remain identical on every row.

Reusing an already imported `session_id` causes that session to be skipped. Do not change an ID merely to bypass duplicate detection.

## Units

GymApp imports kilograms only.

- If the source explicitly uses kilograms, transcribe the value directly.
- If the source explicitly uses pounds, convert with `kg = lb * 0.45359237` and round to a sensible precision such as `0.1 kg`.
- If the unit is unclear, ask the user. Do not guess.
- For dumbbells, preserve the number written by the user. Do not double a per-hand value.
- For assisted exercises, `weight_kg` means assistance weight, not lifted body weight.

## RIR Conversion In GymApp

Numeric RIR is stored in the app's effort bands:

- `0` becomes `At limit`
- `1` or `2` becomes `1-2 left`
- `3` or `4` becomes `3-4 left`
- `5` or more becomes `5+ / easy`

Older sessions retain their original legacy effort labels instead of being
reinterpreted into the new ranges.

Leave `rir` blank if it was not recorded. Imported sets without RIR remain visible but do not prove automatic permanent plan progression.

## Example

```csv
format_version,session_id,date,start_time,gym,workout_name,duration_minutes,exercise_order,exercise_id,exercise_name,set_number,set_type,status,weight_kg,reps,seconds,meters,rir,rest_seconds,set_duration_seconds,exercise_notes,session_notes
1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,1,,Chest Press Machine,1,working,completed,60,12,,,2,,38,Seat 4,Felt strong
1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,1,,Chest Press Machine,2,working,completed,65,10,,,1,95,41,,
1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,2,,Triceps Pushdown,1,working,completed,25,12,,,2,110,34,,
```

## Import Behavior

GymApp validates the file and shows a preview before writing data. Every exercise must resolve to the user's **My exercises** list. Unknown exercises must be mapped to an existing definition or defined manually, including whether the value is weight, added weight, or assistance. Historical imports create completed standalone sessions:

- They appear in History and Progress.
- They retain the recorded gym.
- They do not modify the permanently saved weights in workout plans.
- Duplicate `session_id` values are skipped.

Use `examples/historical-workouts.csv` as a machine-readable starting point.
