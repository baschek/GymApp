# GymApp Workout Plan CSV Standard

## Purpose

GymApp imports one saved workout per UTF-8 CSV file. The same file defines the exercises and exact set counts for Normal, Fast, and Ultra Fast modes. The importer validates and previews the entire file before saving anything.

Current format version: `1`

## Required Header

```csv
format_version,plan_name,order,exercise_id,exercise_name,normal_sets,fast_sets,ultra_fast_sets,target_metric,target_min,target_max,starting_weight_kg,target_rir_min,target_rir_max,rest_seconds,superset_group
```

All headers must be present. Optional values may be left empty.

| Column | Required | Valid values | Meaning |
| --- | --- | --- | --- |
| `format_version` | Yes | `1` | Identifies this schema. |
| `plan_name` | Yes | Text | Must be identical on every row; one CSV represents one plan. |
| `order` | Yes | Unique positive integer | Exercise order after omitted mode rows are removed. |
| `exercise_id` | No | ID from the user's **My exercises** list | Stable exact match when the ID is already known. Normally leave it blank in files created outside the app. |
| `exercise_name` | Yes | Text | Display name and fallback matching key. |
| `normal_sets` | Yes | `0`–`4` | Sets in Normal mode; `0` omits the exercise. |
| `fast_sets` | Yes | `0`–`4` | Sets in Fast mode; `0` omits the exercise. |
| `ultra_fast_sets` | Yes | `0`–`4` | Sets in Ultra Fast mode; `0` omits the exercise. |
| `target_metric` | Yes | `reps`, `seconds`, `meters` | Primary metric used for targets and progression. |
| `target_min` | Yes | Positive number | Lower end of the shared working-set target. |
| `target_max` | Yes | Number at least `target_min` | Upper end; use the same value for an exact target. |
| `starting_weight_kg` | No | Non-negative number | Initial saved working weight or assistance. |
| `target_rir_min` | No | `0`–`4` | Minimum desired reserve; defaults to `1`. |
| `target_rir_max` | No | `0`–`4` | Maximum desired reserve; defaults to `2`. |
| `rest_seconds` | No | Non-negative integer | Advisory rest duration; defaults to `90`. |
| `superset_group` | No | Short text such as `A` | Equal values group non-standard superset exercises. |

## Import Matching

1. `exercise_id` is matched first without case sensitivity.
2. The normalized `exercise_name` is matched against names and aliases in the user's **My exercises** list.
3. Any unresolved row is shown in the import preview.
4. The user must map it to an existing definition or manually define the exercise, including its measurement type.

When a saved plan already has the same name, the preview offers Replace, Import as copy, or Skip. Replacement changes only the reusable definition. Existing dated workout logs remain unchanged.

## Examples

### Repetition Exercise

```csv
1,Upper Body,1,,Chest Press Machine,3,2,1,reps,8,12,20,1,2,90,
```

### Assisted Pull-up

Assistance is entered in kilograms and lower values are harder.

```csv
1,Pull,2,,Assisted Pull-up,3,2,1,reps,6,10,40,1,2,120,
```

Before resolving this row, define the exercise as **Assistance and repetitions**. This tells GymApp that a lower assistance weight represents progress.

### Farmer's Carry

Define this exercise with **Weight, distance, and duration**. This row selects seconds as the primary progression target.

```csv
1,Full Body,5,,Farmer's Carry,2,1,0,seconds,30,45,16,1,2,120,
```

### Superset

```csv
1,Upper Body,2,,Alternate Incline Dumbbell Curl,3,2,0,reps,8,12,10,1,2,30,A
1,Upper Body,3,,Triceps Pushdown,3,2,0,reps,8,12,20,1,2,90,A
```

## Spreadsheet Guidance

- Use one row per exercise, not one row per set.
- Save or export as UTF-8 comma-separated CSV.
- Quote text containing commas; Excel and Google Sheets handle this automatically.
- Add a workout-specific note during the active workout instead of storing permanent notes in the plan.
- Download the in-app template or start from [`examples/full-body.csv`](../examples/full-body.csv).
