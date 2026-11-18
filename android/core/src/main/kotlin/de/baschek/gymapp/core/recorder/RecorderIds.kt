package de.baschek.gymapp.core.recorder

/** The recorder accepts only navigation identifiers. Unknown inputs retain the event, never its payload. */
internal object RecorderIds {
    private fun words(ids: String) = ids.trim().split(Regex("""\s+""")).toSet()

    val screens = words("""
        add_exercise bests celebration connection edit_set editor editor_amount editor_rest editor_weight
        einstellungen end_confirm exercise exercise_done exercise_menu exercises fortschritt frequency goals gym
        health heute history history_detail home koppeln machine maschine mehr mode more navigation note
        notification pack pairing pause pick plaene plan plan_editor plans progress rest select_day session
        set_edit sets settings setup spontaneous start_gym start_mode start_pack start_plans start_spontaneous
        steigerung summary sync teilen today train train_center train_exercises train_left train_note
        train_pulse train_right train_sets training training_done training_edit training_effort training_empty
        training_left training_rest training_right training_set training_setup uebungen uebungen_detail verlauf
        verlauf_detail watch wear weights workout ziele zusammenfassung
    """)
    val elements = words("""
        abbrechen accent_dunst accent_papier accent_salbei accent_sand add_addon add_exercise add_param
        add_photo add_set adjustment_dismiss adjustment_shown adjustment_undo aenderung_speichern amount
        andere_plaene anstrengung arbeitsgewichte archive archive_cancel archive_confirm archive_open
        auf_handy_einrichten auf_handy_oeffnen aufwaermsatz aufzeichnung_ans_handy_senden back beenden
        bei_jedem_training_aufzeichnen bestleistungen bisherige_saetze cancel change_gym check_update clear_day
        confirm conflict_mine conflict_newest conflict_open control create create_confirm create_plan
        create_profile dauer_am_rand days_minus days_plus disable dismiss_day_workout done edit_done edit_reps
        edit_start edit_weight effort einstellungen enable erlauben_und_starten erneut fertig filter_all
        filter_group filter_gym finish finish_cancel finish_confirm finish_open fortschritt_verlauf fortsetzen
        gesundheit gewicht_aendern hint_apply hint_close hint_open install_update jetzt jump_chip jump_row
        keep_mine keep_newest keep_screen_on live_update live_update_later live_update_settings loeschen log_set
        los mark_reviewed meine menge_aendern minus minus_s missing_back month_next month_prev move_down move_up
        muscle muscle_row nach_oben nach_unten nach_update_suchen naechste_uebung
        navigation_aufzeichnen_test_nur_diese_version navrec_export navrec_switch neueste next next_open
        noch_ein_satz note note_open note_save notice_seen notif_allow notif_cancel notif_later notif_settings
        notiz number_cancel number_ok open_conflict open_exercise open_exercises open_goals open_list
        open_machine open_plan open_session open_settings pair pause_aendern period pick_exercise pick_gym
        plans_collapse plans_expand plus plus15 profile puls_bei_jedem_training_aufzeichnen puls_starten
        remove_addon remove_exercise remove_param rename_open rename_save reopen repair reps_minus reps_plus
        reps_step rest_plus rest_skip restore resume resume_all resume_exercise resume_pinned resume_set retry
        rueckgaengig s satz satz_geschafft satz_hinzufuegen satz_ueberspringen save save_notes seed_demo segment
        select_alternative select_gym select_mode set_row_tap sets_minus sets_plus setup_add setup_add_lehne
        setup_add_sitz setup_open setup_remove setup_save share share_open skip skip_equipment skip_exercise
        skip_open skip_other skip_pain skip_rest skip_time speichern spontan sprechen_oder_tippen start
        start_day_workout start_session step stopp_und_speichern superset sync_now system_notifications
        tab_history tab_more tab_plans tab_progress tab_today tap tap_next time_left_hide time_left_show
        timer_cancel timer_cancel_other timer_start toggle_addon toggle_archive toggle_day_pack toggle_pack
        training_beenden training_fortsetzen trainingshaeufigkeit ueberspringen uebung uebung_hinzufuegen undo
        unpair unskip_exercise update_installieren value_type verbindung verlauf vibration view
        vorschlag_uebernehmen warmup_insert warmup_open wdh_aendern weight weight_step weight_type weiter
        wert_aendern wert_erhoehen wert_verringern wieder_aufnehmen workout_laden zeit_starten
        zeitwert_speichern zurueck zusatzgewicht
    """)
    val actions = words("""
        AddExercise AddSet ChangeRest CheckUpdate CorrectSet DeleteSet Finish FinishSetup Focus InstallUpdate
        Log Move NextExercise OpenPhone OpenPhoneHealthSettings Resolve Retry SetDurationRing SetHeartAuto
        SetNote SkipRest SkipSet StartDay StartPlan StartRecording StartSetTimer StartSpontaneous ToggleExercise
        TogglePack Undo Warmup
    """)
    val causes = words("auto:event auto:start auto:phase auto:remote_event auto:rest_end auto:rest_tick auto:setup auto:session_end auto:summary")
}
