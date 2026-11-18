package de.baschek.gymapp.core.recorder

/** The always-on recorder accepts only navigation identifiers. Unknown inputs retain the event, never its payload. */
internal object RecorderIds {
    private fun words(ids: String) = ids.trim().split(Regex("""\s+""")).toSet()

    val screens = words("""
        bests celebration connection edit_set editor_amount editor_rest editor_weight einstellungen end_confirm
        exercise exercise_done exercise_menu exercises fortschritt frequency goals gym health heute history
        history_detail home koppeln machine maschine mehr mode more navigation notification pack pairing pause pick plaene
        plan plan_editor plans progress rest select_day session settings setup spontaneous steigerung summary
        sync teilen today train train_center train_exercises train_left train_note train_pulse train_right
        train_sets training training_edit uebungen uebungen_detail verlauf verlauf_detail watch wear weights
        workout ziele zusammenfassung start_plans start_spontaneous start_gym start_mode start_pack sets add_exercise
        pick pause exercise_done setup edit_set
    """)
    val elements = words("""
        accent_dunst accent_papier accent_salbei accent_sand add_addon add_exercise add_param add_photo add_set
        adjustment_dismiss adjustment_shown adjustment_undo amount archive archive_cancel archive_confirm
        archive_open back cancel change_gym check_update clear_day confirm conflict_mine conflict_newest
        conflict_open control create create_confirm create_plan create_profile days_minus days_plus disable
        dismiss_day_workout done edit_done edit_reps edit_start edit_weight effort enable filter_all
        filter_group filter_gym finish finish_cancel finish_confirm finish_open hint_apply hint_close hint_open
        install_update jump_chip jump_row keep_mine keep_newest keep_screen_on live_update live_update_later
        live_update_settings log_set mark_reviewed missing_back month_next month_prev move_down move_up muscle
        muscle_row navrec_delete navrec_export next next_open note note_open note_save notice_seen notif_allow
        notif_cancel notif_later notif_settings number_cancel number_ok open_conflict open_exercise
        open_exercises open_goals open_list open_machine open_plan open_session open_settings pair period
        pick_exercise pick_gym plans_collapse plans_expand plus15 profile remove_addon remove_exercise
        remove_param rename_open rename_save reopen repair reps_minus reps_plus reps_step rest_plus rest_skip
        restore resume resume_all resume_exercise resume_pinned resume_set retry save save_notes seed_demo
        segment select_alternative select_gym select_mode set_row_tap sets_minus sets_plus setup_add setup_add_sitz setup_add_lehne setup_open
        setup_remove setup_save share share_open skip skip_equipment skip_exercise skip_open skip_other
        skip_pain skip_rest skip_time start start_day_workout start_session step superset sync_now
        system_notifications tab_history tab_more tab_plans tab_progress tab_today tap tap_next time_left_hide
        time_left_show timer_cancel timer_cancel_other timer_start toggle_addon toggle_archive toggle_day_pack
        toggle_pack undo unpair unskip_exercise value_type vibration view warmup_insert warmup_open weight
        weight_step weight_type delete_set edit_rest open_phone open_health open_connection minus15 toggle_setting
    """)
    val actions = words("""
        AddExercise AddSet ChangeRest CheckUpdate CorrectSet DeleteSet Finish FinishSetup Focus InstallUpdate
        Log Move NextExercise OpenPhone OpenPhoneHealthSettings Resolve Retry SetDurationRing SetHeartAuto
        SkipRest SkipSet StartDay StartPlan StartRecording StartSetTimer StartSpontaneous ToggleExercise
        TogglePack Undo Warmup
    """)
    val causes = words("auto:remote_event auto:rest_end auto:rest_tick auto:setup auto:session_end auto:summary")
}
