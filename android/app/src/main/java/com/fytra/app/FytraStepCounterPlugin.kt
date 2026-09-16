package com.fytra.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@CapacitorPlugin(
    name = "FytraStepCounter",
    permissions = [
        Permission(strings = [Manifest.permission.ACTIVITY_RECOGNITION], alias = "activity"),
        // ΝΕΟ: ξεχωριστό permission alias για τη μόνιμη ειδοποίηση του Foreground Service (Android 13+).
        // Raw string (όχι Manifest.permission.POST_NOTIFICATIONS) ώστε να μη χρειάζεται compileSdk 33+.
        Permission(strings = ["android.permission.POST_NOTIFICATIONS"], alias = "notifications")
    ]
)
class FytraStepCounterPlugin : Plugin(), SensorEventListener {

    companion object {
        const val TAG = "FytraDebug"

        // ΝΕΟ (Σενάριο Β — καταγραφή βημάτων όσο η εφαρμογή είναι ΕΝΤΕΛΩΣ κλειστή):
        // SharedPreferences keys, εντελώς ξεχωριστά/ανεξάρτητα από το God Mode/v1/v2 filter.
        const val CLOSED_APP_PREFS_NAME = "FytraStepPrefs"
        const val KEY_LAST_RAW_STEP_COUNTER = "lastRawStepCounterValue"

        // ============================================================================
        // ΚΕΝΤΡΙΚΗ ΡΥΘΜΙΣΗ v2 (FYTRA STEP ENGINE v2 — SHADOW MODE)
        // Όλες οι σταθερές του v2 εδώ, με σχόλιο μονάδας/σκοπού — καμία "μαγική τιμή" σκόρπια στον κώδικα.
        // ΚΑΜΙΑ από αυτές δεν επηρεάζει το πραγματικό (v1/legacy) μέτρημα — μόνο το παράλληλο v2 shadow.
        // ============================================================================
        const val V2_TARGET_WINDOW_SEC = 2.0            // πόσα δευτερόλεπτα ιστορικού κρατάμε για περιοδικότητα/robust στατιστικές
        const val V2_MIN_BUFFER_SAMPLES = 20             // ελάχιστο μέγεθος ring buffer (ασφάλεια σε πολύ αργή δειγματοληψία)
        const val V2_MAX_BUFFER_SAMPLES = 400            // μέγιστο μέγεθος ring buffer (ασφάλεια σε πολύ γρήγορη δειγματοληψία/μνήμη)
        const val V2_AUTOCORR_MIN_LAG_SEC = 0.25         // ταχύτατο βήμα/τρέξιμο (~240 βήματα/λεπτό όριο ασφαλείας)
        const val V2_AUTOCORR_MAX_LAG_SEC = 1.10         // πολύ αργό βάδισμα (~55 βήματα/λεπτό όριο ασφαλείας)
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από 2ο πραγματικό shadow test με πλήρη διαγνωστικά): το αρχικό 0.35 ήταν καθαρή
        // υπόθεση, ΟΧΙ μέτρηση. Τα πραγματικά logs έδειξαν ότι το κανονικό περπάτημα με το κινητό στο χέρι
        // (όχι σταθεροποιημένο) δίνει periodicity συνήθως 0.13-0.31 — πολύ πιο θορυβώδες σήμα απ' όσο
        // υποθέσαμε. Ξεκάθαρα μη-περπάτημα (μεγάλα κενά/artifacts) έδειξε 0.03-0.08. Νέο όριο ανάμεσα στα
        // δύο, βασισμένο σε πραγματικά δεδομένα.
        const val V2_AUTOCORR_MIN_PERIODICITY = 0.10     // ελάχιστη κανονικοποιημένη συσχέτιση [0..1] — αναθεωρήθηκε από 0.35 σε 0.10 βάσει πραγματικών μετρήσεων
        const val V2_AUTOCORR_RECOMPUTE_EVERY_N = 8      // υπολογίζουμε autocorrelation κάθε Ν δείγματα (έλεγχος κόστους CPU), όχι σε κάθε δείγμα
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από 4ο πραγματικό test με χρονομετρημένες φάσεις): η "βάση" προσαρμοζόταν
        // πολύ γρήγορα (~1 δευτ.) πάνω σε ΟΠΟΙΑΔΗΠΟΤΕ παρατεταμένη κίνηση (είτε περπάτημα είτε κούνημα),
        // οπότε ΠΟΤΕ δεν "εκπλησσόταν" αρκετά ώστε να τιμωρήσει το κούνημα — παρόλο που τα πραγματικά
        // δεδομένα δείχνουν καθαρή διαφορά (gyroRms ~8-11 στο κούνημα έναντι ~2-3 στο τρέξιμο). Πολύ πιο
        // αργή προσαρμογή τώρα, ώστε η βάση να αντιπροσωπεύει το "πραγματικά ήσυχο" επίπεδο για πολύ
        // περισσότερη ώρα, δίνοντας στο γυροσκόπιο πραγματική ευκαιρία να ξεχωρίσει παρατεταμένο κούνημα.
        const val V2_GYRO_BASELINE_ALPHA = 0.999         // ~20 δευτ. σταθερά προσαρμογής (πριν: 0.98 ≈ 1 δευτ.)
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό shadow test): το αρχικό όριο (2.6x) ήταν πολύ αυστηρό — το φυσιολογικό
        // κούνημα χεριού ΚΑΤΑ το περπάτημα ανεβάζει το γυροσκόπιο πολύ πάνω από τη "ήσυχη" βάση, οπότε η πύλη
        // απέρριπτε ΚΑΘΕ πραγματικό βήμα, όχι μόνο τυχαίες χειρονομίες. Χαλαρώνουμε δραστικά μέχρι να έχουμε
        // πραγματικά δεδομένα σύγκρισης ΓΙΑ ΝΑ βαθμονομήσουμε σωστά — προτιμότερο να ΜΗΝ γίνεται καθόλου
        // αυστηρός φραγμός τώρα, παρά να μπλοκάρει αδίκως πραγματικό βάδισμα.
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό test όπου ΟΛΟ το session ήταν κούνημα, χωρίς καμία στιγμή ηρεμίας
        // πριν): η βάση "κυνηγούσε" το ίδιο το σταθερό κούνημα προς τα πάνω (0.59->1.77), οπότε η αναλογία
        // ΠΟΤΕ δεν έφτανε το παλιό όριο 6.0x, αν και το ακατέργαστο γυροσκόπιο ήταν ξεκάθαρα ασυνήθιστο
        // (gyroRms 2.6-9.6, έναντι 0.2-2.0 σε πραγματικό περπάτημα). Χαμηλότερο όριο τώρα, ΣΥΝ ανώτατο
        // όριο στη βάση παρακάτω (V2_GYRO_BASELINE_MAX_CAP) ώστε να ΜΗΝ μπορεί ποτέ να "μάθει" ότι το
        // κούνημα είναι φυσιολογικό, όποια κι αν είναι η αρχική στιγμή που ξεκίνησε.
        const val V2_GYRO_HIGH_RATIO = 3.0               // πόσες φορές πάνω από τη βάση θεωρείται "έντονη περιστροφή"
        const val V2_GYRO_BASELINE_MAX_CAP = 1.0         // απόλυτο ανώτατο όριο για τη "ήσυχη" βάση (διασταυρώθηκε με πραγματικά δεδομένα περπατήματος, όπου η βάση φτάνει φυσιολογικά ~1.0-1.2 — χαμηλότερο καπάκι θα παραμόρφωνε άδικα το πραγματικό περπάτημα)
        const val V2_GYRO_PENALTY_REJECT_ABOVE = 0.85    // πόσο υψηλή πρέπει να είναι η ποινή για να απορριφθεί ΤΕΛΙΚΑ ένα χτύπημα (πολύ πιο ανεκτικό από πριν)
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από 3ο πραγματικό shadow test): το v2 έχανε πολλά πραγματικά βήματα (90 vs 20 σε
        // πραγματικό περπάτημα) — μόνο 1 "V2_REJECT" σε όλη τη συνεδρία σημαίνει ότι σπάνια έφτανε καν σε
        // υποψήφιο "χτύπημα" εξαρχής, άρα το ίδιο το κατώφλι έντασης ήταν πιο αυστηρό απ' όσο χρειαζόταν.
        const val V2_MEDIAN_MAD_K = 2.2                  // χαλαρώθηκε από 3.2 βάσει πραγματικών μετρήσεων (λιγότερα χαμένα βήματα)
        const val V2_MIN_THRESHOLD = 0.30                // απόλυτο ελάχιστο κατώφλι έντασης (ίδιες μονάδες με v1)
        const val V2_MAX_THRESHOLD = 6.0                 // απόλυτο μέγιστο κατώφλι έντασης — ασφάλεια ενάντια σε ακραία δεδομένα
        // ΝΕΟ (μετά από 3ο test): βρέθηκε ψευδές θετικό (4 "βήματα" v2 μέσα στα πρώτα 3-4 δευτ. της σεσίας,
        // ΠΡΙΝ το πραγματικό μετρήσει έστω 1) — πιθανότατα επειδή η "ήσυχη βάση" του γυροσκοπίου δεν είχε
        // προλάβει ακόμα να προσαρμοστεί από την αρχική γενική τιμή στο πραγματικό ήσυχο επίπεδο αυτής της
        // συγκεκριμένης συσκευής/κράτησης. Αγνοούμε υποψήφια χτυπήματα τις πρώτες στιγμές μετά το start().
        const val V2_STARTUP_GRACE_PERIOD_MS = 1500L
        const val V2_MIN_STEP_INTERVAL_MS = 300L         // ίδιο απόλυτο όριο ασφαλείας με v1 (~200 βήματα/λεπτό)
        const val V2_MAX_STEP_INTERVAL_MS = 2000L        // ΠΛΑΤΥΤΕΡΟ από το v1 (850ms) -> δεν αποκλείει ηλικιωμένους/πολύ αργό βάδισμα
        const val V2_MAX_INTERVAL_RATIO_CHANGE = 1.8     // ελαφρώς πιο ανεκτικό από v1 (1.6)
        const val V2_REQUIRED_CONSECUTIVE = 3            // ίδιο ξεκίνημα με v1 — η βελτίωση είναι η ποιότητα κάθε ελέγχου
        const val V2_LOG_EVERY_N_LEGACY_STEPS = 5        // πόσο συχνά καταγράφουμε σύγκριση legacy/v2 στα logs

        // ============================================================================
        // ΝΕΟ: ΣΤΑΘΜΙΣΜΕΝΗ ΒΑΘΜΟΛΟΓΙΑ ΕΜΠΙΣΤΟΣΥΝΗΣ (walk confidence score) — αντικαθιστά την παλιά αλυσίδα
        // "περιοδικότητα ΚΑΙ γυροσκόπιο πρέπει ΚΑΙ ΤΑ ΔΥΟ να περάσουν" με ένα σταθμισμένο άθροισμα.
        // Το διάστημα/αναλογία ΠΑΡΑΜΕΝΟΥΝ απόλυτες πύλες (δεν αλλάζουν) — μόνο η περιοδικότητα+γυροσκόπιο
        // συνδυάζονται πλέον ΠΡΟΣΘΕΤΙΚΑ, ώστε ένα αδύναμο σήμα σε ΕΝΑ εργαλείο να μην απορρίπτει αυτόματα
        // το βήμα — έτσι μπορούμε να προσθέτουμε κι άλλα εργαλεία στο μέλλον χωρίς να "κολλαρίζουν" μεταξύ τους.
        // ============================================================================
        const val V2_WEIGHT_PERIODICITY = 0.65           // βάρος περιοδικότητας στη συνολική βαθμολογία [0..1]
        const val V2_WEIGHT_GYRO = 0.35                  // βάρος γυροσκοπίου (1 - ποινή) στη συνολική βαθμολογία [0..1]
        const val V2_MIN_WALK_CONFIDENCE = 0.45          // ελάχιστη συνολική βαθμολογία [0..1] για αποδοχή — αρχική εκτίμηση, θα βαθμονομηθεί με νέα πραγματικά δεδομένα

        // ============================================================================
        // ΝΕΟ: ΒΑΡΟΜΕΤΡΟ ΩΣ ΤΕΛΙΚΟΣ ΕΠΙΒΕΒΑΙΩΤΗΣ ΟΜΑΔΑΣ ΒΗΜΑΤΩΝ (bout-level, ΟΧΙ ανά μεμονωμένο βήμα).
        // Η αλλαγή πίεσης από ΕΝΑ βήμα είναι πολύ μικρή/θορυβώδης για αξιόπιστη ανά-βήμα απόφαση, αλλά σε
        // ομάδες βημάτων (π.χ. 5-10) μια πραγματική μετακίνηση στο χώρο αφήνει ένα συνεπές, μετρήσιμο ίχνος
        // — κάτι που ΚΑΝΕΝΑ κούνημα χεριού δεν μπορεί ποτέ να αναπαράγει (δεν αλλάζει πραγματικό υψόμετρο).
        // ΠΡΟΣ ΤΟ ΠΑΡΟΝ: μόνο ΔΙΑΓΝΩΣΤΙΚΗ καταγραφή (shadow) — ΔΕΝ απορρίπτει ή εγκρίνει τίποτα ακόμα,
        // μέχρι να έχουμε πραγματικά δεδομένα σύγκρισης.
        const val V2_BAROMETER_BUFFER_MAX = 300          // μέγιστο μέγεθος ιστορικού πίεσης (ασφάλεια μνήμης)
        const val V2_BAROMETER_TREND_WINDOW_MS = 8000L   // παράθυρο ανάλυσης τάσης πίεσης (8 δευτ. ≈ αρκετά βήματα)

        // ============================================================================
        // "GOD MODE" — τελικό φρένο ανάγκης πάνω στο v1, βασισμένο σε ΠΡΑΓΜΑΤΙΚΑ δεδομένα από τα
        // χρονομετρημένα τεστ. Βλ. πλήρες σχόλιο στο godModeShouldSuppressCredit().
        // ============================================================================
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό test): το παράθυρο των 6 χρειαζόταν πολλή ώρα να "γεμίσει" πριν
        // αντιδράσει, αφήνοντας "ριπές" από 3-10 φανταστικά βήματα να περνούν κάθε φορά που η βάση μόλις
        // είχε ενημερωθεί ή η ένταση κούνησης είχε μια στιγμιαία, μικρή πτώση. Μικρότερο παράθυρο +
        // χαμηλότερο όριο = αντιδρά πολύ πιο γρήγορα. ΑΣΦΑΛΕΣ για πραγματικό περπάτημα, αφού εκεί το
        // gyroPenalty παραμένει ΠΑΝΤΑ 0 (η αναλογία δεν ξεπερνάει ποτέ το 3.0x) — δεν αλλάζει τίποτα σε
        // αυτό, όποιο κι αν είναι το όριο εδώ.
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από επιτυχημένο test): το προηγούμενο 4/0.6 δούλεψε πολύ καλά μετά τα πρώτα
        // ~155 δευτερόλεπτα, αλλά χρειαζόταν λίγο χρόνο "ζεστάματος" στην αρχή κάθε νέας συνεδρίας πριν
        // αρχίσει να μπλοκάρει σταθερά. Ακόμα μικρότερο παράθυρο + χαμηλότερο όριο = αντιδρά σχεδόν
        // αμέσως. Παραμένει ΑΣΦΑΛΕΣ για πραγματικό περπάτημα, αφού εκεί το gyroPenalty είναι πάντα 0.
        const val GODMODE_GYRO_WINDOW = 3                // πόσα διαδοχικά ήδη-εγκεκριμένα "βήματα" εξετάζουμε
        const val GODMODE_GYRO_SUSPECT_THRESHOLD = 0.45  // μέσος όρος gyroPenalty πάνω από αυτό = σταθερά ύποπτο
        const val GODMODE_BAROMETER_CONFIRM_HPA = 0.03   // μεταβολή πίεσης (hPa) που θεωρείται αδιάψευστη απόδειξη πραγματικής μετακίνησης

        // ΝΕΟ (μετά από πραγματικό test): βρέθηκε ότι κάποιος μπορεί να αλλάξει τρόπο κουνήματος στα μισά
        // (από περιστροφικό σε πιο κάθετο/χτυπητό) και να αποφύγει ΕΝΤΕΛΩΣ το γυροσκόπιο (gyroPenalty≈0),
        // ενώ ταυτόχρονα παράγει ΑΣΥΝΗΘΙΣΤΑ υψηλή ένταση (πολύ πάνω από φυσιολογικό περπάτημα, βλ. δεδομένα:
        // πραγματικά βήματα σε όλα τα τεστ μας ήταν σχεδόν πάντα κάτω από ~2.5-3.0). Δεύτερο, ΑΝΕΞΑΡΤΗΤΟ
        // μονοπάτι υποψίας — αρκεί το ΕΝΑ από τα δύο (γυροσκόπιο Ή ένταση) για να ενεργοποιηθεί ο έλεγχος
        // βαρομέτρου, όχι και τα δύο μαζί.
        const val GODMODE_AMPLITUDE_WINDOW = 6            // πόσα διαδοχικά ήδη-εγκεκριμένα "βήματα" εξετάζουμε
        const val GODMODE_AMPLITUDE_SUSPECT_THRESHOLD = 3.0 // μέση ένταση πάνω από αυτό = ασυνήθιστα υψηλή, ύποπτη

        // ΝΕΟ (ΠΡΑΓΜΑΤΙΚΟ, τρίτο μονοπάτι υποψίας): αν το κινητό μείνει σχεδόν εντελώς οριζόντιο
        // (παράλληλα στο έδαφος) για πολλά συνεχόμενα "βήματα", είναι φυσικά αδύνατο να είναι πραγματικό
        // περπάτημα -- κανείς δεν περπατάει κρατώντας μόνιμα το κινητό σαν δίσκο. Μεγαλύτερο παράθυρο
        // (10, όχι 4-6) γιατί εδώ ΔΕΝ χρειάζεται γρήγορη αντίδραση -- η οριζόντια θέση είναι από μόνη της
        // ήδη σπάνια σε πραγματική χρήση, οπότε δεν χρειάζεται να "πιάσουμε" νωρίς σαν το κούνημα χεριού.
        const val GODMODE_HORIZONTAL_WINDOW = 5
        const val GODMODE_HORIZONTAL_SUSPECT_THRESHOLD = 0.85
        // ΝΕΟ: αν μια μεμονωμένη τιμή δείξει έστω και ελάχιστη κλίση (κάτω από αυτό), αδειάζουμε αμέσως
        // όλη τη λίστα -- δεν περιμένουμε τον μέσο όρο να "ξεπλυθεί" σταδιακά μέσα σε πολλά βήματα.
        const val GODMODE_HORIZONTAL_ESCAPE_THRESHOLD = 0.90

        // ============================================================================
        // ΝΕΟ: PHASE-CORRELATION ΓΥΡΟΣΚΟΠΙΟΥ — τρίτο, πειραματικό εργαλείο. ΠΡΟΣ ΤΟ ΠΑΡΟΝ ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ
        // (καταγραφή σε logs), ΔΕΝ επηρεάζει ακόμα το πραγματικό μέτρημα — βλ. πλήρη εξήγηση στο σχόλιο
        // πάνω από τη συνάρτηση recordPhaseCorrelation(). Ο λόγος που ΔΕΝ το ενεργοποιούμε κατευθείαν σαν
        // 4ο μονοπάτι υποψίας: υπάρχει πραγματικός κίνδυνος να μπερδέψει το "κρατάω το κινητό πολύ σταθερά
        // ενώ περπατάω απαλά" (πραγματικό, θέλουμε να μετράει) με το "χτυπάω το κινητό χωρίς να περπατάω"
        // (ψεύτικο, θέλουμε να ΜΗΝ μετράει) — και τα δύο μπορεί να δείξουν ελάχιστη περιστροφή. Πρώτα
        // χρειαζόμαστε πραγματικά δεδομένα σύγκρισης, ΑΚΡΙΒΩΣ όπως κάναμε με κάθε άλλο εργαλείο μέχρι τώρα.
        // ============================================================================
        const val PHASE_GYRO_PEAK_MIN_INTERVAL_MS = 150L   // ελάχιστο διάστημα ανάμεσα σε διαδοχικές "κορυφές" γυροσκοπίου (αποφυγή διπλοκαταμέτρησης θορύβου)
        const val PHASE_MATCH_WINDOW_MS = 400L             // πόσο κοντά (σε ms) πρέπει να είναι μια κορυφή γυροσκοπίου σε ένα βήμα για να θεωρηθεί "ταίριασμα"
        const val PHASE_HISTORY_SIZE = 6                   // πόσα πρόσφατα βήματα εξετάζουμε για τον ρυθμό ταιριάσματος

        // ============================================================================
        // ΝΕΟ: ΣΥΝΕΠΕΙΑ ΚΑΤΕΥΘΥΝΣΗΣ ΑΝΑ ΑΞΟΝΑ (X/Y/Z) — τέταρτο, πειραματικό εργαλείο. ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ
        // προς το παρόν (καταγραφή σε logs), ΔΕΝ επηρεάζει το πραγματικό μέτρημα ακόμα.
        // Η ιδέα: ένα χτύπημα στο ίδιο σημείο του κινητού παράγει δύναμη σχεδόν πάντα από την ΙΔΙΑ
        // ακριβώς κατεύθυνση κάθε φορά. Ένα πραγματικό βήμα, επειδή το σώμα κινείται σε 3 διαστάσεις
        // ταυτόχρονα (κάθετη αναπήδηση + οριζόντια κίνηση + πλάγια ταλάντωση χεριού), έχει φυσιολογικά
        // λίγο διαφορετική κατεύθυνση σε κάθε βήμα. Μετράμε το πόσο "όμοια" είναι η κατεύθυνση διαδοχικών
        // χτυπημάτων (συνημιτονική ομοιότητα, 1.0 = πανομοιότυπη κατεύθυνση, 0.0 = εντελώς διαφορετική).
        // ============================================================================
        const val AXIS_DIRECTION_HISTORY_SIZE = 6           // πόσα πρόσφατα βήματα εξετάζουμε για μέση ομοιότητα κατεύθυνσης

        // ============================================================================
        // ΝΕΟ: ΟΡΙΖΟΝΤΙΑ ΚΛΙΣΗ (HORIZONTAL_ORIENTATION) — έκτο, πειραματικό εργαλείο, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ.
        // ΔΙΑΦΟΡΕΤΙΚΟ από το GRAVITY_DRIFT: αυτό δεν μετράει ΑΛΛΑΓΗ στάσης με τον χρόνο — μετράει τη
        // ΤΡΕΧΟΥΣΑ θέση του κινητού ΤΩΡΑ. Αν το κινητό είναι σχεδόν επίπεδο (οριζόντιο, σαν να είναι πάνω
        // σε τραπέζι), το "κάτω" (βαρύτητα) δείχνει σχεδόν αποκλειστικά προς τον άξονα Z (μέσα από την
        // οθόνη), όχι προς τους άξονες X/Y. Το φυσικό περπάτημα σπάνια κρατάει το κινητό έτσι.
        // ============================================================================
        const val HORIZONTAL_ORIENTATION_HISTORY_SIZE = 6

        // ============================================================================
        // ΝΕΟ: ΣΥΝΕΠΕΙΑ ΕΝΤΑΣΗΣ (AMPLITUDE_CONSISTENCY) — έβδομο, πειραματικό εργαλείο, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ.
        // Μετράει πόσο ελάχιστα διαφέρει η ένταση διαδοχικών χτυπημάτων μεταξύ τους (ως ποσοστό της μέσης
        // έντασης). Χαμηλή διακύμανση = ύποπτα «πανομοιότυπα» χτυπήματα.
        // ============================================================================
        const val AMPLITUDE_CONSISTENCY_HISTORY_SIZE = 6

        // ============================================================================
        // ΝΕΟ: ΗΣΥΧΙΑ ΑΝΑΜΕΣΑ ΣΕ ΒΗΜΑΤΑ (INTER_STEP_STILLNESS) — όγδοο, πειραματικό εργαλείο, ΜΟΝΟ
        // ΔΙΑΓΝΩΣΤΙΚΟ. Μετράει πόσο "ήσυχο" (χωρίς κίνηση) είναι το κινητό στο χρονικό διάστημα ΑΝΑΜΕΣΑ
        // σε δύο διαδοχικά υποψήφια βήματα -- όχι στην ίδια τη στιγμή του χτυπήματος. Το πραγματικό σώμα
        // δεν "ηρεμεί" ποτέ εντελώς ανάμεσα σε βήματα (αναπνοή, ταλάντωση, ανακατανομή βάρους). Ένας
        // πραγματικά ακίνητος χρήστης που μόνο χτυπάει θα δείχνει σχεδόν απόλυτη ησυχία ανάμεσα.
        // ============================================================================

        // ============================================================================
        // ΝΕΟ: "ΑΚΙΝΗΣΙΑ ΣΤΑΣΗΣ" (gravity drift) — πέμπτο, πειραματικό εργαλείο. ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ προς το
        // παρόν. Η ιδέα: ακόμα κι αν κρατάς το χέρι "σταθερό", το πραγματικό περπάτημα μετατοπίζει
        // σταδιακά το κέντρο βάρους του σώματος, αλλάζοντας ελαφρώς την κλίση του κινητού ως προς το
        // έδαφος στη διάρκεια πολλών βημάτων. Αν κάποιος είναι ΕΝΤΕΛΩΣ ακίνητος και μόνο χτυπάει με το
        // δάχτυλο, η "κλίση" (κατεύθυνση βαρύτητας όπως τη μετράει το κινητό) θα παραμένει σχεδόν πάγια.
        // ΔΕΝ αγγίζει καθόλου το ήδη υπάρχον φίλτρο βαρύτητας (gravity[]) που χρησιμοποιεί το v1 —
        // απλώς "φωτογραφίζει" την ήδη υπολογισμένη τιμή περιοδικά, για σύγκριση.
        // ============================================================================
        const val GRAVITY_DRIFT_WINDOW_MS = 8000L           // παράθυρο ανάλυσης (8 δευτ. ≈ αρκετά βήματα)
        const val GRAVITY_DRIFT_BUFFER_MAX = 200            // μέγιστο μέγεθος ιστορικού (ασφάλεια μνήμης)
    }

    private lateinit var sensorManager: SensorManager

    // Οι "επίσημοι" αισθητήρες βημάτων της Samsung — τους κρατάμε ΜΟΝΟ για διαγνωστικά logs πλέον.
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null
    private var loadError: String? = null

    // Δικός μας, native αλγόριθμος μέτρησης βημάτων πάνω στον απλό επιταχυνσιόμετρο (accelerometer).
    private var linearAccelSensor: Sensor? = null
    private var rawAccelSensor: Sensor? = null
    private var usingLinearAccel = false

    // ΝΕΟ (v2, μόνο διαγνωστικά/βελτίωση ποιότητας gravity — ΔΕΝ αλλάζει το v1 μέτρημα).
    private var osGravitySensor: Sensor? = null
    private var osGravity = FloatArray(3)
    private var osGravityAvailable = false

    // ΝΕΟ (v2 ΜΟΝΟ): γυροσκόπιο για anti-gesture/anti-rotation scoring.
    private var gyroSensor: Sensor? = null
    private var hasGyroscope = false
    private var gyroBaselineRms = 0.02
    private var latestGyroRms = 0.0

    // ΝΕΟ (v2 ΜΟΝΟ, shadow/διαγνωστικό προς το παρόν): βαρόμετρο ως τελικός επιβεβαιωτής ΟΜΑΔΩΝ βημάτων —
    // βλ. σχόλιο στις σταθερές V2_BAROMETER_* παραπάνω.
    private var barometerSensor: Sensor? = null
    private var hasBarometer = false
    private var barometerHistory = ArrayDeque<Pair<Long, Float>>() // (timestampMs, hPa), bounded size

    // ΝΕΟ (phase-correlation, μόνο διαγνωστικό προς το παρόν): κρατάμε πότε συνέβη η τελευταία "κορυφή"
    // περιστροφής γυροσκοπίου, ώστε να ελέγχουμε αν βρίσκεται κοντά χρονικά σε κάθε πιστωμένο βήμα.
    private var lastGyroPeakTimeMs = 0L
    private var gyroRisingForPeak = false
    private val recentPhaseMatches = ArrayDeque<Boolean>() // true = βρέθηκε κοντινή κορυφή γυροσκοπίου, false = καμία

    // ΝΕΟ (συνέπεια κατεύθυνσης ανά άξονα, ΜΟΝΟ διαγνωστικό προς το παρόν): η κανονικοποιημένη κατεύθυνση
    // (x,y,z) του προηγούμενου "χτυπήματος", και το πρόσφατο ιστορικό ομοιότητας διαδοχικών κατευθύνσεων.
    private var lastPeakDirection: DoubleArray? = null
    private val recentDirectionSimilarities = ArrayDeque<Double>()

    // ΝΕΟ (καθετότητα, ΜΟΝΟ διαγνωστικό προς το παρόν): τι ποσοστό της δύναμης του τελευταίου δείγματος
    // ήταν κάθετο — ενημερώνεται σε κάθε δείγμα, καταγράφεται στο ιστορικό μόνο στα υποψήφια χτυπήματα.
    private var lastVerticalityRatio = 1.0
    private val recentVerticalityRatios = ArrayDeque<Double>()

    // ΝΕΟ (ακινησία στάσης, ΜΟΝΟ διαγνωστικό): ιστορικό στιγμιότυπων της κατεύθυνσης βαρύτητας
    // (χρόνος, [x,y,z]) — ΔΕΝ επηρεάζει καθόλου το ήδη υπάρχον φίλτρο gravity[] του v1.
    private val gravityHistory = ArrayDeque<Pair<Long, FloatArray>>()

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    // ΝΕΟ (v2 μόνο): ΞΕΧΩΡΙΣΤΟ background thread για gravity/gyroscope, ώστε το thread του επιταχυνσιόμετρου
    // (που τροφοδοτεί το ΠΑΡΑΓΩΓΙΚΟ v1 μέτρημα) να ΜΗΝ επιβαρύνεται καθόλου από επιπλέον sensor events —
    // πλήρης απομόνωση, ίδιος ακριβώς χρονισμός με πριν την προσθήκη του v2.
    private var v2AuxSensorThread: HandlerThread? = null
    private var v2AuxSensorHandler: Handler? = null

    // --- Κατάσταση του αλγορίθμου ανίχνευσης βήματος v1 (peak detection με προσαρμοστικό όριο) ---
    // ΑΥΤΟ ΤΟ ΤΜΗΜΑ ΕΙΝΑΙ 100% ΑΝΕΠΑΦΟ ΑΠΟ ΤΗΝ ΠΡΟΗΓΟΥΜΕΝΗ, ΔΟΚΙΜΑΣΜΕΝΗ ΕΚΔΟΣΗ.
    private var gravity = FloatArray(3)
    private var gravityInit = false
    private var vSmooth = 0.0
    private var vSmoothInit = false

    // ΝΕΟ (ακινησία στάσης, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): ΞΕΧΩΡΙΣΤΟ, ΠΟΛΥ ΑΡΓΟ φίλτρο βαρύτητας — ΔΕΝ είναι το ίδιο
    // με το gravity[] παραπάνω. Το gravity[] (πάνω) είναι γρήγορο (alpha=0.8) και προορίζεται για το
    // πραγματικό μέτρημα βημάτων — ΔΕΝ το αγγίζουμε ΚΑΘΟΛΟΥ. Αυτό εδώ είναι ΞΕΧΩΡΙΣΤΗ μεταβλητή, πολύ πιο
    // αργή (alpha=0.995, ~2 δευτ. σταθερά προσαρμογής), ώστε να μην "μολύνεται" από μεμονωμένα χτυπήματα —
    // χρησιμοποιείται ΜΟΝΟ από το recordGravityDrift() παρακάτω, πουθενά αλλού.
    private var slowGravity = FloatArray(3)
    private var slowGravityInit = false

    // ΝΕΟ (οριζόντια κλίση, ΜΟΝΟ διαγνωστικό): ιστορικό του ποσοστού βαρύτητας που έπεφτε στον άξονα Z
    // (μέσα-έξω από την οθόνη) σε σχέση με το σύνολο -- 1.0 = κινητό εντελώς επίπεδο/οριζόντιο.
    private val recentHorizontalRatios = ArrayDeque<Double>()
    private var lastHorizontalRatio = 0.0

    // ΝΕΟ (ΠΡΑΓΜΑΤΙΚΟ φρένο, όχι πια μόνο διαγνωστικό): ξεχωριστό παράθυρο για το God Mode, ίδιας
    // λογικής με recentGyroPenalties/recentAmplitudes -- 10 συνεχόμενα "βήματα" με το κινητό σχεδόν
    // εντελώς οριζόντιο θεωρείται φυσικά αδύνατο για πραγματικό περπάτημα.
    private val recentHorizontalForGodMode = ArrayDeque<Double>()

    // ΝΕΟ (συνέπεια έντασης, ΜΟΝΟ διαγνωστικό): ιστορικό των τελευταίων εντάσεων για να δούμε πόσο
    // ελάχιστα διαφέρουν μεταξύ τους (συντελεστής διακύμανσης).
    private val recentAmplitudesForConsistency = ArrayDeque<Double>()

    // ΝΕΟ (ησυχία ανάμεσα σε βήματα, ΜΟΝΟ διαγνωστικό): συσσωρεύει το επίπεδο κίνησης ΣΕ ΚΑΘΕ δείγμα
    // (όχι μόνο στα χτυπήματα), από το προηγούμενο "βήμα" μέχρι τώρα — μηδενίζεται σε κάθε νέο χτύπημα.
    private var interStepEnergySum = 0.0
    private var interStepSampleCount = 0
    private var trendSlow = 0.0
    private var trendInit = false
    private var lastSampleSign = 0
    private var lastRisingZeroCrossMs = 0L
    private val ZERO_CROSS_TO_PEAK_MAX_MS = 400L
    private var lowPassMag = 0.0
    private var lowPassInit = false
    private var ampEstimate = 1.2
    private var rising = false
    private var lastPeakTimeMs = 0L
    private var internalStepCount = 0L
    private var algoEventCount = 0L
    private var usingVerticalProjection = false

    private val MIN_STEP_INTERVAL_MS = 300L
    // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό test μόνο-περπατήματος): το 850ms ήταν πολύ στενό — αντιστοιχεί σε
    // ρυθμό πάνω από ~70 βήματα/λεπτό, οπότε ΟΠΟΙΟΔΗΠΟΤΕ πιο χαλαρό/αργό βήμα (πολύ συνηθισμένο,
    // ειδικά στην αρχή ενός περπατήματος) ξαναμηδένιζε την αναμονή για 3 συνεπή βήματα, προκαλώντας
    // ακριβώς το φαινόμενο "κάνω 10 βήματα, ακόμα δείχνει 0, μετά πηδάει ξαφνικά". Πλατύτερο όριο,
    // ΙΔΙΟ με αυτό που είχαμε ήδη δοκιμάσει/βαθμονομήσει νωρίτερα για το v2.
    private val MAX_STEP_INTERVAL_MS = 2000L
    private val REQUIRED_CONSECUTIVE_FOR_WALKING = 3
    // ΔΙΟΡΘΩΘΗΚΕ: ίδιο πνεύμα με το MAX_STEP_INTERVAL_MS παραπάνω — ευθυγραμμίστηκε με το v2.
    private val MAX_INTERVAL_RATIO_CHANGE = 1.8
    private val MAX_AMPLITUDE_COEFF_OF_VARIATION = 0.65
    private var pendingPeaks = mutableListOf<Triple<Long, Double, Double>>() // (χρόνος, ένταση, gyroPenalty)
    private var lastIntervalMs: Long = -1L
    private var isWalkingConfirmed = false

    // ============================================================================
    // FYTRA STEP ENGINE v2 — SHADOW MODE STATE
    // Τίποτα εδώ κάτω ΔΕΝ αγγίζει το internalStepCount / notifyListeners του v1.
    // ============================================================================
    private var v2InternalStepCount = 0L
    private var v2PendingPeaks = mutableListOf<Pair<Long, Double>>()
    private var v2LastIntervalMs: Long = -1L
    private var v2IsWalkingConfirmed = false
    private var v2Rising = false
    private var v2LastPeakTimeMs = 0L
    private var v2RejectLogCounter = 0L
    private var v2SessionStartTimeMs = 0L

    private var lastEventTimestampNs: Long = 0L
    private var estimatedSampleIntervalMs = 20.0
    private var v2RingBufferSize = 100

    private var v2SignalBuffer = DoubleArray(V2_MAX_BUFFER_SAMPLES)
    private var v2SignalBufferCount = 0
    private var v2SignalBufferHead = 0
    private var v2SamplesSinceAutocorr = 0
    private var v2LastPeriodicityScore = 0.0
    private var v2LastDominantPeriodMs = 0L

    override fun load() {
        Log.d(TAG, "load() ΞΕΚΙΝΗΣΕ")
        try {
            sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            rawAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            osGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            hasGyroscope = gyroSensor != null
            // ΝΕΟ: το βαρόμετρο περνάει τώρα από "μόνο έλεγχος" σε "αποθηκεύεται για πραγματική χρήση"
            // ως shadow/διαγνωστικός επιβεβαιωτής ομάδων βημάτων.
            barometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            hasBarometer = barometerSensor != null
            Log.d(TAG, "load() ΤΕΛΕΙΩΣΕ. stepCounterSensor=" + stepCounterSensor + " stepDetectorSensor=" + stepDetectorSensor +
                " linearAccelSensor=" + linearAccelSensor + " rawAccelSensor=" + rawAccelSensor +
                " [v2] osGravitySensor=" + osGravitySensor + " gyroSensor=" + gyroSensor +
                " [BAROMETER] barometerSensor=" + barometerSensor)
        } catch (e: Exception) {
            loadError = e.javaClass.simpleName + ": " + e.message
            Log.d(TAG, "load() ΕΣΚΑΣΕ: " + loadError)
        }
    }

    @PluginMethod
    fun requestStepPermissions(call: PluginCall) {
        Log.d(TAG, "requestStepPermissions() ΚΛΗΘΗΚΕ")
        try {
            if (loadError != null) { Log.d(TAG, "requestStepPermissions: loadError υπάρχει, reject"); call.reject("load() failed: " + loadError); return }
            val already = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "requestStepPermissions: already granted = " + already)
            if (already) {
                val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
                Log.d(TAG, "requestStepPermissions: resolve(granted=true) ΕΓΙΝΕ")
            } else {
                Log.d(TAG, "requestStepPermissions: καλώ requestPermissionForAlias")
                requestPermissionForAlias("activity", call, "permissionCallback")
            }
        } catch (e: Exception) {
            Log.d(TAG, "requestStepPermissions ΕΣΚΑΣΕ: " + e.message)
            call.reject("requestStepPermissions crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        Log.d(TAG, "permissionCallback() ΚΛΗΘΗΚΕ")
        try {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
            Log.d(TAG, "permissionCallback: resolve(granted=" + granted + ") ΕΓΙΝΕ")
        } catch (e: Exception) {
            Log.d(TAG, "permissionCallback ΕΣΚΑΣΕ: " + e.message)
            call.reject("permissionCallback crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ: εντελώς ξεχωριστό permission flow, ΜΟΝΟ για τη μόνιμη ειδοποίηση του Foreground Service
    // (Android 13+ απαιτεί ρητή runtime άδεια POST_NOTIFICATIONS, το να το δηλώσουμε μόνο στο manifest
    // δεν αρκεί). Καμία σχέση/επίδραση με το requestStepPermissions()/ACTIVITY_RECOGNITION παραπάνω ή
    // με το God Mode/v1/v2 filter — αμιγώς για να φαίνεται η ειδοποίηση στον χρήστη.
    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        Log.d(TAG, "requestNotificationPermission() ΚΛΗΘΗΚΕ")
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                // Πριν το Android 13 δεν υπάρχει καν αυτό το permission — οι ειδοποιήσεις δουλεύουν κανονικά χωρίς αυτό.
                val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
                return
            }
            val already = ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            if (already) {
                val ret = JSObject(); ret.put("granted", true); call.resolve(ret)
            } else {
                requestPermissionForAlias("notifications", call, "notificationPermissionCallback")
            }
        } catch (e: Exception) {
            Log.d(TAG, "requestNotificationPermission ΕΣΚΑΣΕ: " + e.message)
            call.reject("requestNotificationPermission crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        Log.d(TAG, "notificationPermissionCallback() ΚΛΗΘΗΚΕ")
        try {
            val granted = if (android.os.Build.VERSION.SDK_INT < 33) true else
                ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            val ret = JSObject(); ret.put("granted", granted); call.resolve(ret)
        } catch (e: Exception) {
            Log.d(TAG, "notificationPermissionCallback ΕΣΚΑΣΕ: " + e.message)
            call.reject("notificationPermissionCallback crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ: συνδυαστικό permission request — ζητάει ΚΑΙ τα δύο (Φυσική δραστηριότητα + Ειδοποιήσεις) ΣΕ ΜΙΑ
    // ενιαία κλήση, ώστε το Android να τα εμφανίσει σαν μία ροή αντί για δύο ξεχωριστά, διαδοχικά dialogs.
    // Οι παλιές, ξεχωριστές μέθοδοι requestStepPermissions()/requestNotificationPermission() παραμένουν
    // ΑΝΕΝΕΡΓΕΣ αλλά άθικτες παραπάνω — δεν καλούνται πια από το κανονικό flow, ασφαλές να μείνουν εκεί.
    // Καμία επίδραση στο God Mode/v1/v2 filter.
    @PluginMethod
    fun requestAllStepPermissions(call: PluginCall) {
        Log.d(TAG, "requestAllStepPermissions() ΚΛΗΘΗΚΕ")
        try {
            val activityGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = if (android.os.Build.VERSION.SDK_INT < 33) true else
                ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            if (activityGranted && notificationsGranted) {
                val ret = JSObject(); ret.put("granted", true); ret.put("activityGranted", true); ret.put("notificationsGranted", true)
                call.resolve(ret)
                return
            }
            requestPermissionForAliases(arrayOf("activity", "notifications"), call, "combinedPermissionCallback")
        } catch (e: Exception) {
            Log.d(TAG, "requestAllStepPermissions ΕΣΚΑΣΕ: " + e.message)
            call.reject("requestAllStepPermissions crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PermissionCallback
    private fun combinedPermissionCallback(call: PluginCall) {
        Log.d(TAG, "combinedPermissionCallback() ΚΛΗΘΗΚΕ")
        try {
            val activityGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = if (android.os.Build.VERSION.SDK_INT < 33) true else
                ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            val ret = JSObject()
            // ΣΗΜΑΝΤΙΚΟ: "granted" (βασικό flag που κοιτάει το JS) αντιστοιχεί ΜΟΝΟ στο activity/βήματα —
            // ένας χρήστης που αρνείται τις ειδοποιήσεις αλλά επιτρέπει τη φυσική δραστηριότητα πρέπει να
            // συνεχίσει να μετράει κανονικά (η ειδοποίηση είναι δευτερεύουσα, ΠΟΤΕ μπλοκάρει τη μέτρηση).
            ret.put("granted", activityGranted)
            ret.put("activityGranted", activityGranted)
            ret.put("notificationsGranted", notificationsGranted)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.d(TAG, "combinedPermissionCallback ΕΣΚΑΣΕ: " + e.message)
            call.reject("combinedPermissionCallback crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        Log.d(TAG, "start() ΚΛΗΘΗΚΕ")
        try {
            if (loadError != null) { Log.d(TAG, "start: loadError υπάρχει, reject"); call.reject("load() failed: " + loadError); return }
            if (linearAccelSensor == null && rawAccelSensor == null) {
                Log.d(TAG, "start: ΔΕΝ υπάρχει κανένας επιταχυνσιόμετρος στη συσκευή, reject")
                call.reject("No accelerometer available on this device")
                return
            }

            resetAlgorithmState()

            if (sensorThread == null) {
                sensorThread = HandlerThread("FytraStepSensorThread").also { it.start() }
                sensorHandler = Handler(sensorThread!!.looper)
            }

            usingLinearAccel = rawAccelSensor == null && linearAccelSensor != null
            usingVerticalProjection = rawAccelSensor != null
            val chosenAccelSensor = if (rawAccelSensor != null) rawAccelSensor else linearAccelSensor
            Log.d(TAG, "start: επιλεγμένος αισθητήρας κίνησης = " + (if (usingVerticalProjection) "TYPE_ACCELEROMETER (κατακόρυφη προβολή)" else "TYPE_LINEAR_ACCELERATION (μέτρο, fallback)"))

            val accelRegistered = sensorManager.registerListener(this, chosenAccelSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            Log.d(TAG, "start: registerListener (accelerometer αλγόριθμος) = " + accelRegistered)

            // ΝΕΟ (v2 μόνο, δεν επηρεάζει το v1): gravity/gyroscope σε ΔΙΚΟ ΤΟΥΣ, ξεχωριστό background thread
            // — ΔΙΟΡΘΩΘΗΚΕ: πριν μοιράζονταν το ΙΔΙΟ thread με τον επιταχυνσιόμετρο, κάτι που θα μπορούσε
            // θεωρητικά να εισάγει μικρές καθυστερήσεις χρονισμού στο ΠΑΡΑΓΩΓΙΚΟ v1 μονοπάτι. Τώρα το
            // thread του επιταχυνσιόμετρου είναι πλήρως απομονωμένο, ΑΚΡΙΒΩΣ όπως πριν την προσθήκη του v2.
            if (v2AuxSensorThread == null) {
                v2AuxSensorThread = HandlerThread("FytraV2AuxSensorThread").also { it.start() }
                v2AuxSensorHandler = Handler(v2AuxSensorThread!!.looper)
            }
            osGravityAvailable = false
            osGravitySensor?.let {
                val ok = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, v2AuxSensorHandler)
                Log.d(TAG, "start: registerListener (v2 gravity sensor, ξεχωριστό thread) = " + ok)
            }
            if (hasGyroscope) {
                val ok = sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME, v2AuxSensorHandler)
                Log.d(TAG, "start: registerListener (v2 gyroscope, ξεχωριστό thread) = " + ok)
            }
            // ΝΕΟ: το βαρόμετρο αλλάζει πολύ αργά (κατάλληλο ~5Hz, ίδιο πνεύμα με gravity/gyroscope) —
            // ξεχωριστό thread, ίδια πολιτική.
            if (hasBarometer) {
                barometerHistory.clear()
                val ok = sensorManager.registerListener(this, barometerSensor, SensorManager.SENSOR_DELAY_NORMAL, v2AuxSensorHandler)
                Log.d(TAG, "start: registerListener (v2 βαρόμετρο, ξεχωριστό thread) = " + ok)
            }

            val mainHandler = Handler(Looper.getMainLooper())
            var counterRegistered = false
            stepCounterSensor?.let {
                counterRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
                Log.d(TAG, "start: registerListener (Samsung step counter, μόνο διαγνωστικά) = " + counterRegistered)
            }
            var detectorRegistered = false
            stepDetectorSensor?.let {
                detectorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, mainHandler)
                Log.d(TAG, "start: registerListener (Samsung step detector, μόνο διαγνωστικά) = " + detectorRegistered)
            }

            val ret = JSObject()
            ret.put("counterRegistered", accelRegistered)
            ret.put("detectorAvailable", true)
            ret.put("detectorRegistered", accelRegistered)
            ret.put("counterSensorName", "FYTRA native βηματόμετρο (" + (if (usingLinearAccel) "linear accelerometer" else "raw accelerometer") + ")")
            ret.put("counterSensorVendor", chosenAccelSensor?.vendor ?: "?")

            // ΝΕΟ: ξεκινάμε το ξεχωριστό Foreground Service (βλ. FytraStepForegroundService.kt) ΜΟΝΟ
            // για να εμφανίσουμε τη μόνιμη ειδοποίηση που εμποδίζει το Android να σκοτώσει τη διεργασία
            // όσο η εφαρμογή είναι στο παρασκήνιο. ΔΕΝ αγγίζει, ΔΕΝ ξαναρχίζει και ΔΕΝ επηρεάζει με
            // ΚΑΝΕΝΑΝ τρόπο τους αισθητήρες/αλγόριθμο μέτρησης που μόλις registerήθηκαν παραπάνω — αυτοί
            // συνεχίζουν να τρέχουν ακριβώς όπως πριν, μέσα σε αυτό το ίδιο plugin.
            try {
                ContextCompat.startForegroundService(context, Intent(context, FytraStepForegroundService::class.java))
                Log.d(TAG, "start: FytraStepForegroundService εκκινήθηκε (μόνιμη ειδοποίηση background)")
            } catch (e: Exception) {
                Log.d(TAG, "start: ΔΕΝ ξεκίνησε το FytraStepForegroundService (μη κρίσιμο, η μέτρηση συνεχίζει κανονικά): " + e.message)
            }

            call.resolve(ret)
            Log.d(TAG, "start: call.resolve() ΕΓΙΝΕ. [v2 shadow ενεργό: hasGravitySensor=" + (osGravitySensor != null) + " hasGyroscope=" + hasGyroscope + "]")
        } catch (e: Exception) {
            Log.d(TAG, "start ΕΣΚΑΣΕ: " + e.message)
            call.reject("start crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun resetAlgorithmState() {
        gravity = FloatArray(3)
        gravityInit = false
        vSmooth = 0.0
        vSmoothInit = false
        trendSlow = 0.0
        trendInit = false
        lastSampleSign = 0
        lastRisingZeroCrossMs = System.currentTimeMillis()
        lowPassMag = 0.0
        lowPassInit = false
        ampEstimate = 1.2
        rising = false
        lastPeakTimeMs = 0L
        internalStepCount = 0L
        algoEventCount = 0L
        pendingPeaks = mutableListOf()
        lastIntervalMs = -1L
        isWalkingConfirmed = false
        consecutiveMisses = 0

        v2InternalStepCount = 0L
        v2PendingPeaks = mutableListOf()
        v2LastIntervalMs = -1L
        v2IsWalkingConfirmed = false
        v2Rising = false
        v2LastPeakTimeMs = 0L
        v2RejectLogCounter = 0L
        barometerHistory.clear()
        recentGyroPenalties.clear()
        recentAmplitudes.clear()
        recentHorizontalForGodMode.clear()
        lastHorizontalRatio = 0.0
        lastGyroPeakTimeMs = 0L
        gyroRisingForPeak = false
        recentPhaseMatches.clear()
        lastPeakDirection = null
        recentDirectionSimilarities.clear()
        lastVerticalityRatio = 1.0
        recentVerticalityRatios.clear()
        gravityHistory.clear()
        slowGravityInit = false
        recentHorizontalRatios.clear()
        recentAmplitudesForConsistency.clear()
        interStepEnergySum = 0.0
        interStepSampleCount = 0
        v2SessionStartTimeMs = System.currentTimeMillis()
        osGravity = FloatArray(3)
        osGravityAvailable = false
        gyroBaselineRms = 0.02
        latestGyroRms = 0.0
        lastEventTimestampNs = 0L
        estimatedSampleIntervalMs = 20.0
        v2RingBufferSize = 100
        v2SignalBuffer = DoubleArray(V2_MAX_BUFFER_SAMPLES)
        v2SignalBufferCount = 0
        v2SignalBufferHead = 0
        v2SamplesSinceAutocorr = 0
        v2LastPeriodicityScore = 0.0
        v2LastDominantPeriodMs = 0L
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        try {
            sensorManager.unregisterListener(this)
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
            v2AuxSensorThread?.quitSafely()
            v2AuxSensorThread = null
            v2AuxSensorHandler = null
            // ΝΕΟ: σταματάμε το Foreground Service (μόνιμη ειδοποίηση) μαζί με τη μέτρηση — συμμετρικό
            // με την εκκίνησή του στο start(). Δεν αγγίζει καθόλου τη λογική μέτρησης παραπάνω.
            try {
                context.stopService(Intent(context, FytraStepForegroundService::class.java))
                Log.d(TAG, "stop: FytraStepForegroundService σταμάτησε")
            } catch (e: Exception) {
                Log.d(TAG, "stop: σφάλμα σταματήματος FytraStepForegroundService (μη κρίσιμο): " + e.message)
            }
            call.resolve()
        } catch (e: Exception) {
            call.reject("stop crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @PluginMethod
    fun getDebugLogs(call: PluginCall) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "6000"))
            val allLines = process.inputStream.bufferedReader().readText()
            val tagPattern = Regex("""\b(FytraDebug|AndroidRuntime|System\.err)\b""")
            val capacitorTagPattern = Regex("""\bCapacitor\s*:""")
            val relevant = allLines.lines().filter { line ->
                tagPattern.containsMatchIn(line) || capacitorTagPattern.containsMatchIn(line)
            }.joinToString("\n")
            val ret = JSObject()
            ret.put("logs", if (relevant.isNotBlank()) relevant else "(δεν βρέθηκαν σχετικές γραμμές μέσα στα τελευταία logs)\n\n--- ΟΛΑ τα logs (τελευταίες γραμμές) ---\n" + allLines.takeLast(3000))
            call.resolve(ret)
        } catch (e: Exception) {
            call.reject("getDebugLogs crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    // ΝΕΟ (v2): on-demand σύγκριση legacy/v2, χωρίς τον θόρυβο όλων των υπόλοιπων logs.
    @PluginMethod
    fun getShadowComparison(call: PluginCall) {
        val ret = JSObject()
        ret.put("legacySteps", internalStepCount.toDouble())
        ret.put("v2Steps", v2InternalStepCount.toDouble())
        ret.put("estimatedHz", if (estimatedSampleIntervalMs > 0) 1000.0 / estimatedSampleIntervalMs else 0.0)
        ret.put("periodicityScore", v2LastPeriodicityScore)
        ret.put("dominantPeriodMs", v2LastDominantPeriodMs.toDouble())
        ret.put("gyroRms", latestGyroRms)
        ret.put("hasGyroscope", hasGyroscope)
        ret.put("hasGravitySensor", osGravitySensor != null)
        call.resolve(ret)
    }

    // ΝΕΟ (Σενάριο Β — καταγραφή βημάτων όσο η εφαρμογή είναι ΕΝΤΕΛΩΣ κλειστή):
    // ==========================================================================================
    // ΑΠΑΡΑΒΑΤΟΣ ΟΡΟΣ: αυτή η μέθοδος είναι 100% απομονωμένη από το God Mode/v1/v2 filter.
    // - Χρησιμοποιεί το ΔΙΚΟ ΤΗΣ, ανώνυμο SensorEventListener (όχι το "this"), άρα ΔΕΝ περνάει ΠΟΤΕ
    //   μέσα από το κεντρικό override fun onSensorChanged(event) που επεξεργάζεται τον επιταχυνσιόμετρο.
    // - ΔΕΝ καλεί resetAlgorithmState(), ΔΕΝ αγγίζει καμία μεταβλητή/σταθερά του God Mode/v1/v2.
    // - Καλείται ΜΟΝΟ μία φορά, στο άνοιγμα της εφαρμογής, ΠΡΙΝ ξεκινήσει η ζωντανή μέτρηση (start()).
    //
    // ΛΟΓΙΚΗ: ο Android hardware step counter (TYPE_STEP_COUNTER) μετράει ΜΟΝΟΣ ΤΟΥ, συνεχώς, μέσα στο
    // chip της συσκευής — ΑΚΟΜΑ και όταν η εφαρμογή είναι εντελώς κλειστή. Συγκρίνουμε την τρέχουσα τιμή
    // του με την τελευταία τιμή που είχαμε αποθηκεύσει (μέσω του onSensorChanged/TYPE_STEP_COUNTER
    // παραπάνω, που τρέχει ΜΟΝΟ όσο η εφαρμογή είναι ζωντανή) για να υπολογίσουμε πόσα βήματα έγιναν στο
    // "κενό" όσο ήταν κλειστή. Αν η νέα τιμή είναι ΜΙΚΡΟΤΕΡΗ από την αποθηκευμένη, σημαίνει ότι έγινε
    // επανεκκίνηση της συσκευής (ο μετρητής μηδενίζεται σε κάθε restart) — σε αυτή την περίπτωση ΔΕΝ
    // μπορούμε να ανακτήσουμε τα ενδιάμεσα βήματα, οπότε επιστρέφουμε delta=0 (ΠΟΤΕ αρνητικό/λάθος νούμερο).
    // ==========================================================================================
    @PluginMethod
    fun checkClosedAppSteps(call: PluginCall) {
        try {
            if (loadError != null) { call.reject("load() failed: " + loadError); return }
            val counterSensor = stepCounterSensor
            if (counterSensor == null) {
                val ret = JSObject(); ret.put("available", false); ret.put("delta", 0); call.resolve(ret)
                return
            }
            // ΝΕΟ / ΔΙΟΡΘΩΣΗ: ο TYPE_STEP_COUNTER αισθητήρας σε πολλές συσκευές (ειδικά Samsung) ΔΕΝ
            // στέλνει καμία τιμή μέχρι να γίνει το επόμενο πραγματικό βήμα — μπορεί να μην απαντήσει ΠΟΤΕ
            // αν ο χρήστης δεν περπατήσει αμέσως. Χωρίς timeout, αυτό μπλόκαρε ΟΛΟΚΛΗΡΗ την εκκίνηση της
            // ζωντανής μέτρησης (God Mode) στο JS layer, αφού εκεί γινόταν await σε αυτό ΠΡΙΝ το
            // ensureStepTrackingStarted(). Με resolved=false guard + Handler timeout (2.5s) εξασφαλίζουμε
            // ότι αυτό το PluginCall ΠΑΝΤΑ απαντάει, ό,τι κι αν γίνει με τον αισθητήρα — καμία επίδραση στο
            // God Mode/v1/v2, απλά διασφαλίζουμε ότι δεν κολλάει τίποτα.
            val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var oneShotListener: SensorEventListener
            val timeoutRunnable = Runnable {
                if (resolved.compareAndSet(false, true)) {
                    try { sensorManager.unregisterListener(oneShotListener) } catch (e: Exception) {}
                    Log.d(TAG, "checkClosedAppSteps: TIMEOUT (ο αισθητήρας δεν απάντησε εγκαίρως) — resolve(available=false)")
                    val ret = JSObject(); ret.put("available", false); ret.put("delta", 0); ret.put("timedOut", true)
                    call.resolve(ret)
                }
            }
            oneShotListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!resolved.compareAndSet(false, true)) return
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    try {
                        sensorManager.unregisterListener(this)
                        val rawValue = if (event.values.isNotEmpty()) event.values[0] else 0f
                        val prefs = context.getSharedPreferences(CLOSED_APP_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        val hasStored = prefs.contains(KEY_LAST_RAW_STEP_COUNTER)
                        val storedValue = prefs.getFloat(KEY_LAST_RAW_STEP_COUNTER, 0f)
                        var recoveredDelta = 0
                        var rebootDetected = false
                        if (hasStored) {
                            if (rawValue >= storedValue) {
                                recoveredDelta = (rawValue - storedValue).toInt()
                            } else {
                                rebootDetected = true
                                Log.d(TAG, "checkClosedAppSteps: εντοπίστηκε επανεκκίνηση συσκευής (νέα τιμή < αποθηκευμένη), delta=0")
                            }
                        }
                        prefs.edit().putFloat(KEY_LAST_RAW_STEP_COUNTER, rawValue).apply()
                        Log.d(TAG, "checkClosedAppSteps: hasStored=$hasStored storedValue=$storedValue rawValue=$rawValue recoveredDelta=$recoveredDelta rebootDetected=$rebootDetected")
                        val ret = JSObject()
                        ret.put("available", true)
                        ret.put("delta", recoveredDelta)
                        ret.put("rebootDetected", rebootDetected)
                        call.resolve(ret)
                    } catch (e: Exception) {
                        call.reject("checkClosedAppSteps (listener) crashed: " + e.javaClass.simpleName + ": " + e.message)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(oneShotListener, counterSensor, SensorManager.SENSOR_DELAY_FASTEST)
            timeoutHandler.postDelayed(timeoutRunnable, 2500L)
        } catch (e: Exception) {
            call.reject("checkClosedAppSteps crashed: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                Log.d(TAG, "onSensorChanged (Samsung step counter, ΜΟΝΟ diag) value=" + (if (event.values.isNotEmpty()) event.values[0] else "?"))
                // ΝΕΟ (Σενάριο Β): αποθηκεύουμε συνεχώς την τελευταία γνωστή τιμή σε SharedPreferences
                // (persistent — επιβιώνει ακόμα κι αν σκοτωθεί η διεργασία) ΜΟΝΟ ώστε το ξεχωριστό
                // checkClosedAppSteps() να μπορεί αργότερα να υπολογίσει πόσα βήματα έγιναν όσο η
                // εφαρμογή ήταν ΕΝΤΕΛΩΣ κλειστή. Αυτός ο κλάδος (TYPE_STEP_COUNTER) ήταν ΗΔΗ "μόνο
                // διαγνωστικός" — άσχετος με το πραγματικό μέτρημα του God Mode/v1/v2 filter, που γίνεται
                // αποκλειστικά στους κλάδους TYPE_LINEAR_ACCELERATION/TYPE_ACCELEROMETER παρακάτω. Καμία
                // επίδραση σε αυτούς.
                if (event.values.isNotEmpty()) {
                    try {
                        context.getSharedPreferences(CLOSED_APP_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                            .edit().putFloat(KEY_LAST_RAW_STEP_COUNTER, event.values[0]).apply()
                    } catch (e: Exception) { /* μη κρίσιμο — δεν επηρεάζει τη μέτρηση */ }
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                Log.d(TAG, "onSensorChanged (Samsung step detector, ΜΟΝΟ diag) ΚΛΗΘΗΚΕ")
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                updateEstimatedSampleRate(event.timestamp)
                processAccelSample(event)
            }
            Sensor.TYPE_GRAVITY -> {
                osGravity[0] = event.values[0]; osGravity[1] = event.values[1]; osGravity[2] = event.values[2]
                osGravityAvailable = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                processGyroSample(event)
            }
            Sensor.TYPE_PRESSURE -> {
                // ΝΕΟ: απλή προσθήκη στο ιστορικό — η ίδια η ανάλυση τάσης γίνεται μόνο όποτε χρειαστεί
                // (κατά την επιβεβαίωση ομάδας βημάτων), όχι σε κάθε δείγμα, για ελάχιστη επιβάρυνση.
                val nowMs = System.currentTimeMillis()
                barometerHistory.addLast(nowMs to event.values[0])
                while (barometerHistory.size > V2_BAROMETER_BUFFER_MAX) barometerHistory.removeFirst()
            }
        }
    }

    // ΝΕΟ (v2): εκτιμά τον πραγματικό ρυθμό δειγματοληψίας από τα ίδια τα timestamps του αισθητήρα.
    private fun updateEstimatedSampleRate(timestampNs: Long) {
        if (lastEventTimestampNs != 0L) {
            val dtMs = (timestampNs - lastEventTimestampNs) / 1_000_000.0
            if (dtMs in 1.0..200.0) {
                estimatedSampleIntervalMs = estimatedSampleIntervalMs * 0.9 + dtMs * 0.1
                val desired = (1000.0 / estimatedSampleIntervalMs * V2_TARGET_WINDOW_SEC).toInt()
                v2RingBufferSize = desired.coerceIn(V2_MIN_BUFFER_SAMPLES, V2_MAX_BUFFER_SAMPLES)
            }
        }
        lastEventTimestampNs = timestampNs
    }

    private fun processGyroSample(event: SensorEvent) {
        val wx = event.values[0]; val wy = event.values[1]; val wz = event.values[2]
        val omega = sqrt((wx * wx + wy * wy + wz * wz).toDouble())
        latestGyroRms = latestGyroRms * 0.8 + omega * 0.2
        if (latestGyroRms < gyroBaselineRms) {
            gyroBaselineRms = gyroBaselineRms * 0.95 + latestGyroRms * 0.05
        } else {
            gyroBaselineRms = gyroBaselineRms * V2_GYRO_BASELINE_ALPHA + latestGyroRms * (1 - V2_GYRO_BASELINE_ALPHA)
        }
        // ΝΕΟ: ΑΠΟΛΥΤΟ καπάκι — χωρίς αυτό, ένα αρκετά μακρύ, σταθερό κούνημα (ακόμα κι αν ξεκινήσει
        // αμέσως, χωρίς καμία στιγμή ηρεμίας πριν) θα μπορούσε τελικά να "πείσει" τη βάση ότι το ίδιο το
        // κούνημα είναι φυσιολογικό, μηδενίζοντας ουσιαστικά την άμυνα του γυροσκοπίου.
        gyroBaselineRms = gyroBaselineRms.coerceAtMost(V2_GYRO_BASELINE_MAX_CAP)
        // ΝΕΟ (phase-correlation, μόνο διαγνωστικό): εντοπίζουμε πότε η περιστροφή ξεπερνάει αισθητά την
        // "ήσυχη" βάση της — αυτό θεωρείται μια "κορυφή" περιστροφής, παρόμοια λογική με το πώς εντοπίζουμε
        // κορυφές επιτάχυνσης στο processAccelSample. Χρησιμοποιείται ΜΟΝΟ για να μετρήσουμε αν συμβαίνει
        // κοντά χρονικά σε κάθε πιστωμένο βήμα — καμία επίδραση στο πραγματικό μέτρημα προς το παρόν.
        val nowMs = System.currentTimeMillis()
        val peakThreshold = gyroBaselineRms * 1.8 + 0.05
        if (latestGyroRms > peakThreshold && !gyroRisingForPeak && (nowMs - lastGyroPeakTimeMs) > PHASE_GYRO_PEAK_MIN_INTERVAL_MS) {
            gyroRisingForPeak = true
            lastGyroPeakTimeMs = nowMs
        } else if (latestGyroRms < peakThreshold * 0.6) {
            gyroRisingForPeak = false
        }
    }

    // ΝΕΟ (phase-correlation, ΜΟΝΟ διαγνωστικό): καλείται στη στιγμή που πιστώνεται ένα βήμα, ελέγχει αν
    // υπήρξε "κορυφή" γυροσκοπίου κοντά χρονικά (μέσα σε PHASE_MATCH_WINDOW_MS), και καταγράφει το ποσοστό
    // ταιριάσματος στα τελευταία PHASE_HISTORY_SIZE βήματα — ΔΕΝ επηρεάζει την απόφαση καταμέτρησης ακόμα.
    private fun recordPhaseCorrelation(stepTimeMs: Long) {
        val matched = abs(stepTimeMs - lastGyroPeakTimeMs) <= PHASE_MATCH_WINDOW_MS
        recentPhaseMatches.addLast(matched)
        while (recentPhaseMatches.size > PHASE_HISTORY_SIZE) recentPhaseMatches.removeFirst()
        if (recentPhaseMatches.size >= PHASE_HISTORY_SIZE) {
            val matchRate = recentPhaseMatches.count { it } / PHASE_HISTORY_SIZE.toDouble()
            Log.d(TAG, "PHASE_CORRELATION matchRate=" + String.format("%.2f", matchRate) + " (τελευταία " + PHASE_HISTORY_SIZE + " βήματα, μόνο διαγνωστικό)")
        }
    }

    // ΝΕΟ (συνέπεια κατεύθυνσης ανά άξονα, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ — βλ. πλήρη εξήγηση στις σταθερές
    // AXIS_DIRECTION_* παραπάνω). Καλείται σε κάθε υποψήφιο χτύπημα, ΠΡΙΝ την τελική απόφαση —
    // υπολογίζει πόσο "όμοια" είναι η κατεύθυνση (x,y,z) με το προηγούμενο χτύπημα.
    private fun recordAxisDirection(x: Float, y: Float, z: Float) {
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        if (mag < 0.01) return // πολύ αδύναμο σήμα για αξιόπιστη κατεύθυνση, αγνοείται
        val dir = doubleArrayOf(x / mag, y / mag, z / mag)

        val prev = lastPeakDirection
        if (prev != null) {
            // Συνημιτονική ομοιότητα: 1.0 = πανομοιότυπη κατεύθυνση, 0.0 = κάθετες, -1.0 = αντίθετη.
            val similarity = prev[0] * dir[0] + prev[1] * dir[1] + prev[2] * dir[2]
            recentDirectionSimilarities.addLast(similarity)
            while (recentDirectionSimilarities.size > AXIS_DIRECTION_HISTORY_SIZE) recentDirectionSimilarities.removeFirst()
            if (recentDirectionSimilarities.size >= AXIS_DIRECTION_HISTORY_SIZE) {
                val avgSimilarity = recentDirectionSimilarities.average()
                Log.d(TAG, "AXIS_DIRECTION avgSimilarity=" + String.format("%.2f", avgSimilarity) + " (τελευταία " + AXIS_DIRECTION_HISTORY_SIZE + " βήματα, μόνο διαγνωστικό — 1.00=ίδια κατεύθυνση κάθε φορά)")
            }
        }
        lastPeakDirection = dir
    }

    // ΝΕΟ (καθετότητα, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): καταγράφει το μέσο ποσοστό καθετότητας των τελευταίων υποψήφιων
    // χτυπημάτων. Χαμηλή μέση τιμή = η δύναμη έρχεται κυρίως πλάγια, όχι κάθετα -- ασυνήθιστο για
    // πραγματικό βήμα, πιθανό σημάδι χτυπήματος από «λάθος» κατεύθυνση.
    private fun recordVerticality() {
        recentVerticalityRatios.addLast(lastVerticalityRatio)
        while (recentVerticalityRatios.size > AXIS_DIRECTION_HISTORY_SIZE) recentVerticalityRatios.removeFirst()
        if (recentVerticalityRatios.size >= AXIS_DIRECTION_HISTORY_SIZE) {
            val avgVerticality = recentVerticalityRatios.average()
            Log.d(TAG, "VERTICALITY avgRatio=" + String.format("%.2f", avgVerticality) + " (τελευταία " + AXIS_DIRECTION_HISTORY_SIZE + " βήματα, μόνο διαγνωστικό — 1.00=όλη η δύναμη κάθετη)")
        }
    }

    // ΝΕΟ (οριζόντια κλίση, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): χρησιμοποιεί το ΗΔΗ υπάρχον, γρήγορο gravity[] (το ίδιο
    // που χρησιμοποιεί το v1) -- εδώ ΔΕΝ χρειαζόμαστε αργό φίλτρο, θέλουμε την τωρινή θέση, όχι αλλαγή με
    // τον χρόνο. Υπολογίζει τι ποσοστό της βαρύτητας πέφτει στον άξονα Z (μέσα-έξω από την οθόνη) σε
    // σχέση με το σύνολο -- 1.0 = κινητό εντελώς επίπεδο/οριζόντιο σαν να είναι πάνω σε τραπέζι.
    private fun recordHorizontalOrientation() {
        val gMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble())
        if (gMag < 0.1) return
        val horizontalRatio = (abs(gravity[2]) / gMag).coerceIn(0.0, 1.0)
        lastHorizontalRatio = horizontalRatio // ΝΕΟ: εκτίθεται στο God Mode, βλ. godModeShouldSuppressCredit()
        recentHorizontalRatios.addLast(horizontalRatio)
        while (recentHorizontalRatios.size > HORIZONTAL_ORIENTATION_HISTORY_SIZE) recentHorizontalRatios.removeFirst()
        if (recentHorizontalRatios.size >= HORIZONTAL_ORIENTATION_HISTORY_SIZE) {
            val avgHorizontal = recentHorizontalRatios.average()
            Log.d(TAG, "HORIZONTAL_ORIENTATION avgRatio=" + String.format("%.2f", avgHorizontal) + " (τελευταία " + HORIZONTAL_ORIENTATION_HISTORY_SIZE + " βήματα, μόνο διαγνωστικό — 1.00=κινητό εντελώς οριζόντιο)")
        }
    }

    // ΝΕΟ (συνέπεια έντασης, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): συντελεστής διακύμανσης (τυπική απόκλιση / μέσος όρος)
    // των τελευταίων εντάσεων -- πολύ χαμηλή τιμή = τα χτυπήματα είναι ύποπτα «πανομοιότυπα».
    private fun recordAmplitudeConsistency(amplitude: Double) {
        recentAmplitudesForConsistency.addLast(amplitude)
        while (recentAmplitudesForConsistency.size > AMPLITUDE_CONSISTENCY_HISTORY_SIZE) recentAmplitudesForConsistency.removeFirst()
        if (recentAmplitudesForConsistency.size >= AMPLITUDE_CONSISTENCY_HISTORY_SIZE) {
            val mean = recentAmplitudesForConsistency.average()
            if (mean > 0.001) {
                val variance = recentAmplitudesForConsistency.map { (it - mean) * (it - mean) }.average()
                val stdDev = sqrt(variance)
                val coeffOfVariation = stdDev / mean
                Log.d(TAG, "AMPLITUDE_CONSISTENCY coeffOfVariation=" + String.format("%.3f", coeffOfVariation) + " (τελευταία " + AMPLITUDE_CONSISTENCY_HISTORY_SIZE + " βήματα, μόνο διαγνωστικό — 0.0=πανομοιότυπη ένταση κάθε φορά)")
            }
        }
    }

    // ΝΕΟ (ησυχία ανάμεσα σε βήματα, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): καταγράφει τον μέσο όρο κίνησης από το προηγούμενο
    // χτύπημα μέχρι τώρα, μετά μηδενίζει τον μετρητή για να ξεκινήσει η μέτρηση του επόμενου διαστήματος.
    private fun recordInterStepStillness() {
        if (interStepSampleCount > 0) {
            val avgEnergy = interStepEnergySum / interStepSampleCount
            Log.d(TAG, "INTER_STEP_STILLNESS avgEnergy=" + String.format("%.4f", avgEnergy) + " samples=" + interStepSampleCount + " (μόνο διαγνωστικό — χαμηλό=απόλυτη ησυχία ανάμεσα σε χτυπήματα)")
        }
        interStepEnergySum = 0.0
        interStepSampleCount = 0
    }

    // ΝΕΟ (ακινησία στάσης, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): συγκρίνει την τωρινή κατεύθυνση βαρύτητας με αυτή από πριν
    // GRAVITY_DRIFT_WINDOW_MS χιλιοστά του δευτερολέπτου — μεγάλη γωνιακή διαφορά = το σώμα/κινητό άλλαξε
    // αισθητά κλίση/στάση (σύμφωνο με πραγματικό περπάτημα), μηδενική διαφορά = απόλυτη ακινησία στάσης.
    // ΔΙΑΒΑΖΕΙ μόνο την ήδη υπάρχουσα μεταβλητή gravity[] — δεν την τροποποιεί, δεν επηρεάζει το v1.
    private fun recordGravityDrift(x: Float, y: Float, z: Float) {
        // ΝΕΟ: ενημερώνει ΜΟΝΟ το δικό του, ξεχωριστό, αργό φίλτρο (slowGravity) — ΔΕΝ αγγίζει το gravity[]
        // που χρησιμοποιεί το πραγματικό μέτρημα βημάτων.
        if (!slowGravityInit) { slowGravity[0] = x; slowGravity[1] = y; slowGravity[2] = z; slowGravityInit = true }
        val slowAlpha = 0.995f // ~2 δευτ. σταθερά προσαρμογής -- πολύ πιο αργό από το γρήγορο gravity[] (alpha=0.8)
        slowGravity[0] = slowAlpha * slowGravity[0] + (1 - slowAlpha) * x
        slowGravity[1] = slowAlpha * slowGravity[1] + (1 - slowAlpha) * y
        slowGravity[2] = slowAlpha * slowGravity[2] + (1 - slowAlpha) * z

        val nowMs = System.currentTimeMillis()
        gravityHistory.addLast(nowMs to slowGravity.copyOf())
        while (gravityHistory.size > GRAVITY_DRIFT_BUFFER_MAX) gravityHistory.removeFirst()

        val windowStart = nowMs - GRAVITY_DRIFT_WINDOW_MS
        val oldest = gravityHistory.firstOrNull { it.first >= windowStart } ?: return
        val (t0, g0) = oldest
        if (nowMs - t0 < GRAVITY_DRIFT_WINDOW_MS * 0.6) return // δεν έχουμε ακόμα αρκετά μεγάλο παράθυρο

        val mag0 = sqrt((g0[0] * g0[0] + g0[1] * g0[1] + g0[2] * g0[2]).toDouble())
        val mag1 = sqrt((slowGravity[0] * slowGravity[0] + slowGravity[1] * slowGravity[1] + slowGravity[2] * slowGravity[2]).toDouble())
        if (mag0 < 0.1 || mag1 < 0.1) return
        val dot = (g0[0] * slowGravity[0] + g0[1] * slowGravity[1] + g0[2] * slowGravity[2]) / (mag0 * mag1)
        val angleDeg = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
        Log.d(TAG, "GRAVITY_DRIFT angleDeg=" + String.format("%.1f", angleDeg) + " (τελευταία " + (GRAVITY_DRIFT_WINDOW_MS / 1000) + "s, μόνο διαγνωστικό — 0.0=καθόλου αλλαγή στάσης)")
    }

    // ΝΕΟ: κοινή συνάρτηση (πριν ήταν διπλότυπη κρυμμένη μέσα στο processV2Shadow) — τώρα τροφοδοτεί ΚΑΙ
    // το νέο "God Mode" φρένο ανάγκης του v1 ΚΑΙ το v2 shadow, με ΑΚΡΙΒΩΣ τον ίδιο υπολογισμό.
    private fun computeGyroPenalty(): Double {
        return if (hasGyroscope && gyroBaselineRms > 0.0001) {
            val ratio = latestGyroRms / gyroBaselineRms
            ((ratio - V2_GYRO_HIGH_RATIO) / V2_GYRO_HIGH_RATIO).coerceIn(0.0, 1.0)
        } else 0.0
    }

    // ⚠️ ΠΑΡΑΓΩΓΙΚΟ ΜΟΝΟΠΑΤΙ (v1/legacy) — ΑΝΕΠΑΦΟ εκτός από μία κλήση στο processV2Shadow() στο τέλος.
    private fun processAccelSample(event: SensorEvent) {
        algoEventCount++
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]

        val hp: Double
        if (usingVerticalProjection) {
            if (!gravityInit) { gravity[0] = x; gravity[1] = y; gravity[2] = z; gravityInit = true }
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
            val gMag = sqrt((gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).toDouble())
            val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
            val vertical = if (gMag > 0.1) (lx * gravity[0] + ly * gravity[1] + lz * gravity[2]) / gMag else 0.0
            // ΝΕΟ (καθετότητα, ΜΟΝΟ ΔΙΑΓΝΩΣΤΙΚΟ): τι ποσοστό της συνολικής τοπικής δύναμης ήταν πραγματικά
            // κάθετο (προς/από το έδαφος) αντί για πλάγιο/οριζόντιο. 1.0 = όλη η δύναμη κάθετη (τυπικό
            // πραγματικό βήμα), χαμηλότερο = μεγάλο μέρος της δύναμης ήταν πλάγιο (πιθανό χτύπημα από
            // ασυνήθιστη κατεύθυνση, π.χ. στο πίσω μέρος του κινητού).
            val localMag = sqrt((lx * lx + ly * ly + lz * lz).toDouble())
            lastVerticalityRatio = if (localMag > 0.01) (abs(vertical) / localMag).coerceIn(0.0, 1.0) else 1.0
            // ΝΕΟ (ησυχία ανάμεσα σε βήματα, ΜΟΝΟ διαγνωστικό): συσσωρεύεται σε ΚΑΘΕ δείγμα, όχι μόνο στα
            // χτυπήματα -- έτσι πιάνει την κίνηση ΑΝΑΜΕΣΑ σε βήματα, όχι μόνο τη στιγμή του χτυπήματος.
            interStepEnergySum += localMag
            interStepSampleCount++
            if (!vSmoothInit) { vSmooth = vertical; vSmoothInit = true }
            vSmooth = vSmooth * 0.7 + vertical * 0.3
            if (!trendInit) { trendSlow = vSmooth; trendInit = true }
            trendSlow = trendSlow * 0.97 + vSmooth * 0.03
            hp = vSmooth - trendSlow
        } else {
            val mag = sqrt((x * x + y * y + z * z).toDouble())
            if (!lowPassInit) { lowPassMag = mag; lowPassInit = true }
            lowPassMag = lowPassMag * 0.9 + mag * 0.1
            hp = mag - lowPassMag
        }

        val now = System.currentTimeMillis()

        val deadzone = 0.05
        val currentSign = when { hp > deadzone -> 1; hp < -deadzone -> -1; else -> lastSampleSign }
        if (lastSampleSign == -1 && currentSign == 1) { lastRisingZeroCrossMs = now }
        lastSampleSign = currentSign
        // ΝΕΟ: υπολογίζεται ΜΙΑ φορά εδώ (κοινή προεπεξεργασία σήματος, όπως η προβολή βαρύτητας/bandpass),
        // ώστε να χρησιμοποιείται με ΤΟΝ ΙΔΙΟ τρόπο ΚΑΙ από το v1 ΚΑΙ από το v2 shadow παρακάτω — δίκαιη
        // σύγκριση, όχι μόνο το v1 να έχει αυτή την άμυνα ενάντια σε απότομα artifacts χωρίς σωστό σχήμα.
        val hasProperWaveShape = (now - lastRisingZeroCrossMs) in 0..ZERO_CROSS_TO_PEAK_MAX_MS
        // ΝΕΟ ("God Mode"): υπολογίζεται ΜΙΑ φορά εδώ, κοινό για v1 και v2 — βλ. computeGyroPenalty().
        val gyroPenalty = computeGyroPenalty()

        ampEstimate = max(ampEstimate * 0.996, abs(hp))
        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικό test): πολύ απαλά, πραγματικά βήματα έπεφταν ΚΑΤΩ από το 0.32 και
        // ΔΕΝ ανιχνεύονταν ΚΑΘΟΛΟΥ (καμία γραμμή καν στα logs). Χαμηλώθηκε μετρημένα σε 0.24 — αρκετό να
        // πιάσει πιο απαλά βήματα, χωρίς να ανοίξει υπερβολικά την πόρτα σε θόρυβο χειρισμού.
        val threshold = max(0.24, ampEstimate * 0.35)

        if (hp > threshold && !rising && (now - lastPeakTimeMs) > MIN_STEP_INTERVAL_MS) {
            rising = true
            val interval = if (lastPeakTimeMs == 0L) -1L else (now - lastPeakTimeMs)
            lastPeakTimeMs = now
            if (hasProperWaveShape) {
                recordAxisDirection(x, y, z) // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                recordVerticality() // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                recordGravityDrift(x, y, z) // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                recordHorizontalOrientation() // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                recordAmplitudeConsistency(hp) // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                recordInterStepStillness() // ΝΕΟ: μόνο διαγνωστικό, βλ. σχόλιο στη συνάρτηση
                handleCandidatePeak(now, interval, hp, gyroPenalty)
            } else {
                Log.d(TAG, "processAccelSample: peak χωρίς καθαρό ανερχόμενο zero-crossing πριν από αυτό -> αγνοείται ως πιθανό artifact")
            }
        } else if (hp < -threshold * 0.3) {
            rising = false
        }

        // ΝΕΟ: v2 shadow — τρέχει ΠΑΝΤΑ παράλληλα, ανεξάρτητα από το αποτέλεσμα του v1 παραπάνω. Περνάμε
        // ΚΑΙ το κοινό hasProperWaveShape/gyroPenalty, ίδια είσοδος με το v1.
        processV2Shadow(now, hp, hasProperWaveShape, gyroPenalty)
    }

    // ⚠️ ΠΑΡΑΓΩΓΙΚΟ ΜΟΝΟΠΑΤΙ (v1). Η ΑΡΧΙΚΗ λογική ανίχνευσης (διάστημα/αναλογία/ένταση/σχήμα κύματος)
    // ΠΑΡΑΜΕΝΕΙ 100% ΑΝΕΠΑΦΗ. Το ΜΟΝΟ που προστέθηκε είναι το godModeShouldSuppressCredit() ΑΚΡΙΒΩΣ
    // πριν από κάθε creditOneStep() — ένα τελευταίο "φρένο ανάγκης" ενάντια σε παρατεταμένο, σκόπιμο
    // κούνημα χεριού, βασισμένο σε πραγματικά μετρημένα δεδομένα (βλ. σχόλιο στη συνάρτηση).
    // ΝΕΟ (μετά από πραγματικά τεστ): πόσα ΣΥΝΕΧΟΜΕΝΑ "λάθος" διαστήματα έχουμε δει πρόσφατα — χρειάζονται
    // 2 ΣΤΗ ΣΕΙΡΑ πριν μηδενίσουμε την πρόοδο, όχι 1. Ένα μεμονωμένο, λίγο πιο αργό/γρήγορο βήμα είναι
    // φυσιολογικό στο πραγματικό ανθρώπινο βάδισμα — δεν πρέπει να "σβήνει" όλη την ήδη-χτισμένη εμπιστοσύνη.
    private var consecutiveMisses = 0

    // ⚠️ ΠΑΡΑΓΩΓΙΚΟ ΜΟΝΟΠΑΤΙ (v1). Η ΒΑΣΙΚΗ λογική (διάστημα/αναλογία/ένταση/σχήμα κύματος) παραμένει ίδια.
    // ΝΕΟ: ανοχή σε ΜΕΜΟΝΩΜΕΝΟ λάθος διάστημα (hysteresis) — βλ. σχόλιο στο consecutiveMisses παραπάνω.
    // Αυτό διορθώνει το ακριβές, μετρημένο πρόβλημα "κάνω 10 βήματα, μετράει 5-6, μετά πηδάει ξαφνικά" —
    // η παλιά λογική μηδένιζε ΟΛΗ την πρόοδο σε κάθε μεμονωμένη φυσιολογική διακύμανση ρυθμού βαδίσματος.
    private fun handleCandidatePeak(now: Long, intervalFromPrevious: Long, amplitude: Double, gyroPenalty: Double) {
        val intervalInRange = intervalFromPrevious in MIN_STEP_INTERVAL_MS..MAX_STEP_INTERVAL_MS
        val ratioOk = if (lastIntervalMs <= 0L || intervalFromPrevious <= 0L) true else {
            val ratio = intervalFromPrevious.toDouble() / lastIntervalMs.toDouble()
            val biggerOverSmaller = if (ratio >= 1.0) ratio else 1.0 / ratio
            biggerOverSmaller <= MAX_INTERVAL_RATIO_CHANGE
        }
        val passesTimingChecks = intervalFromPrevious != -1L && intervalInRange && ratioOk

        if (isWalkingConfirmed) {
            if (passesTimingChecks) {
                consecutiveMisses = 0
                lastIntervalMs = intervalFromPrevious
                if (!godModeShouldSuppressCredit(gyroPenalty, amplitude)) creditOneStep(now)
            } else {
                consecutiveMisses++
                if (consecutiveMisses >= 2) {
                    // ΠΡΑΓΜΑΤΙΚΑ 2 στη σειρά ασυνεπή -> εύλογο να πιστέψουμε ότι σταμάτησε το περπάτημα.
                    isWalkingConfirmed = false
                    lastIntervalMs = -1L
                    consecutiveMisses = 0
                    pendingPeaks = mutableListOf(Triple(now, amplitude, gyroPenalty))
                }
                // ΝΕΟ: στο 1ο μεμονωμένο λάθος, ΔΕΝ μηδενίζουμε τίποτα — απλά αγνοούμε ΑΥΤΟ το χτύπημα
                // (δεν μετράει, αλλά ΔΕΝ χάνεται η ήδη επιβεβαιωμένη κατάσταση περπατήματος) και περιμένουμε
                // το επόμενο, συγκρίνοντάς το ΑΚΟΜΑ με το τελευταίο ΚΑΛΟ διάστημα (lastIntervalMs αμετάβλητο).
            }
            return
        }

        if (!passesTimingChecks) {
            consecutiveMisses++
            if (consecutiveMisses >= 2 || pendingPeaks.isEmpty()) {
                pendingPeaks = mutableListOf(Triple(now, amplitude, gyroPenalty))
                lastIntervalMs = -1L
                consecutiveMisses = 0
            }
            // ΝΕΟ: στο 1ο μεμονωμένο λάθος ΠΡΙΝ καν επιβεβαιωθεί περπάτημα, κρατάμε ό,τι έχουμε ήδη
            // συγκεντρώσει (pendingPeaks) αντί να τα πετάξουμε όλα — μειώνει τις άδικες καθυστερήσεις
            // στην αρχή κάθε περπατήματος.
            return
        }

        consecutiveMisses = 0
        lastIntervalMs = intervalFromPrevious
        pendingPeaks.add(Triple(now, amplitude, gyroPenalty))
        if (pendingPeaks.size >= REQUIRED_CONSECUTIVE_FOR_WALKING) {
            val amplitudes = pendingPeaks.map { it.second }
            val meanAmp = amplitudes.average()
            val variance = amplitudes.sumOf { (it - meanAmp) * (it - meanAmp) } / amplitudes.size
            val coeffOfVariation = if (meanAmp > 0.0001) sqrt(variance) / meanAmp else 0.0

            if (coeffOfVariation <= MAX_AMPLITUDE_COEFF_OF_VARIATION) {
                isWalkingConfirmed = true
                val toCredit = pendingPeaks.toList()
                pendingPeaks = mutableListOf()
                toCredit.forEach { (t, amp, gp) -> if (!godModeShouldSuppressCredit(gp, amp)) creditOneStep(t) }
            } else {
                pendingPeaks = mutableListOf(pendingPeaks.last())
            }
        }
    }

    // ============================================================================
    // "GOD MODE" — τελικό φρένο ανάγκης πάνω στο ΗΔΗ αξιόπιστο v1. ΔΕΝ αγγίζει καθόλου τη λογική
    // ανίχνευσης παραπάνω — μπαίνει ΜΟΝΟ ως τελευταίος έλεγχος, αφού το βήμα έχει ήδη εγκριθεί από
    // διάστημα/αναλογία/ένταση/σχήμα κύματος. Βασισμένο σε ΠΡΑΓΜΑΤΙΚΑ μετρημένα δεδομένα (χρονομετρημένα
    // τεστ): το γυροσκόπιο (με αργή, 20-δεύτερη βάση) έδειξε ξεκάθαρα υψηλότερη, σταθερή τιμή κατά τη
    // διάρκεια σκόπιου κουνήματος χεριού σε σχέση με πραγματικό περπάτημα/τρέξιμο. Ενεργοποιείται ΜΟΝΟ
    // όταν ΠΟΛΛΑ ΔΙΑΔΟΧΙΚΑ ήδη-εγκεκριμένα "βήματα" δείχνουν εξαιρετικά υψηλό γυροσκόπιο — ένα μεμονωμένο
    // τίναγμα ΠΟΤΕ δεν αρκεί. Το βαρόμετρο, όταν δείξει πραγματική μετακίνηση, ΠΑΝΤΑ υπερισχύει.
    // ============================================================================
    private val recentGyroPenalties = ArrayDeque<Double>()
    private val recentAmplitudes = ArrayDeque<Double>()

    private fun godModeShouldSuppressCredit(gyroPenalty: Double, amplitude: Double): Boolean {
        recentGyroPenalties.addLast(gyroPenalty)
        while (recentGyroPenalties.size > GODMODE_GYRO_WINDOW) recentGyroPenalties.removeFirst()
        recentAmplitudes.addLast(amplitude)
        while (recentAmplitudes.size > GODMODE_AMPLITUDE_WINDOW) recentAmplitudes.removeFirst()
        // ΝΕΟ (ΠΡΑΓΜΑΤΙΚΟ φρένο): τρίτο, ανεξάρτητο μονοπάτι — σχεδόν οριζόντιο κινητό για πολλά
        // συνεχόμενα "βήματα" (βλ. σχόλιο στη σταθερά GODMODE_HORIZONTAL_WINDOW).
        // ΝΕΟ: "άμεση διαφυγή" -- αν αυτή η μεμονωμένη τιμή δείχνει έστω και ελάχιστη κλίση, αδειάζουμε
        // αμέσως τη λίστα, ώστε να μη χρειαστεί να περιμένουμε τον μέσο όρο να πέσει σταδιακά.
        if (lastHorizontalRatio < GODMODE_HORIZONTAL_ESCAPE_THRESHOLD) recentHorizontalForGodMode.clear()
        recentHorizontalForGodMode.addLast(lastHorizontalRatio)
        while (recentHorizontalForGodMode.size > GODMODE_HORIZONTAL_WINDOW) recentHorizontalForGodMode.removeFirst()

        val gyroSuspicious = recentGyroPenalties.size >= GODMODE_GYRO_WINDOW &&
            recentGyroPenalties.average() >= GODMODE_GYRO_SUSPECT_THRESHOLD
        val amplitudeSuspicious = recentAmplitudes.size >= GODMODE_AMPLITUDE_WINDOW &&
            recentAmplitudes.average() >= GODMODE_AMPLITUDE_SUSPECT_THRESHOLD
        val horizontalSuspicious = recentHorizontalForGodMode.size >= GODMODE_HORIZONTAL_WINDOW &&
            recentHorizontalForGodMode.average() >= GODMODE_HORIZONTAL_SUSPECT_THRESHOLD

        if (!gyroSuspicious && !amplitudeSuspicious && !horizontalSuspicious) return false // κανένα από τα τρία ανεξάρτητα μονοπάτια δεν είναι ύποπτο

        // ΔΙΟΡΘΩΘΗΚΕ (μετά από πραγματικά logs): το βαρόμετρο ΑΦΑΙΡΕΘΗΚΕ από εδώ ως "διαφυγή" — real
        // δεδομένα έδειξαν ότι μεταβολές πίεσης 0.03-0.15 hPa (το κατώφλι "επιβεβαίωσης") εμφανίζονται
        // ΕΞΙΣΟΥ σε πραγματικό περπάτημα ΚΑΙ σε καθαρό κούνημα/θόρυβο σε επίπεδο έδαφος — δεν είναι
        // αξιόπιστο "τελικό λόγο". Έτσι, όποτε γυροσκόπιο Ή ένταση Ή οριζόντια στάση δείχνουν σταθερά
        // ύποπτα, ΤΩΡΑ μπλοκάρεται ΠΑΝΤΑ — το βαρόμετρο παραμένει μόνο διαγνωστικό (καταγράφεται στα logs).
        val baroTrend = v2BarometerTrend()
        Log.d(TAG, "GODMODE_SUPPRESS: gyroSuspicious=" + gyroSuspicious + " (avg=" + String.format("%.2f", if (recentGyroPenalties.isNotEmpty()) recentGyroPenalties.average() else 0.0) + ")" +
            " amplitudeSuspicious=" + amplitudeSuspicious + " (avg=" + String.format("%.2f", if (recentAmplitudes.isNotEmpty()) recentAmplitudes.average() else 0.0) + ")" +
            " horizontalSuspicious=" + horizontalSuspicious + " (avg=" + String.format("%.2f", if (recentHorizontalForGodMode.isNotEmpty()) recentHorizontalForGodMode.average() else 0.0) + ")" +
            " βαρόμετρο(μόνο διαγν.)=" + (if (baroTrend != null) String.format("%.4f", baroTrend) else "n/a") + " -> ΥΠΟΨΙΑ ΣΚΟΠΙΜΟΥ ΚΟΥΝΗΜΑΤΟΣ, το βήμα ΔΕΝ μετράει")
        return true
    }

    private fun creditOneStep(atTimeMs: Long) {
        internalStepCount++
        // ΝΕΟ (phase-correlation, ΜΟΝΟ διαγνωστικό) — καλείται ΕΔΩ, ΜΕΤΑ την πίστωση, άρα ΔΕΝ μπορεί ποτέ
        // να εμποδίσει ή να ακυρώσει το ήδη πιστωμένο βήμα.
        recordPhaseCorrelation(atTimeMs)
        val data = JSObject()
        data.put("totalSteps", internalStepCount.toDouble())
        notifyListeners("stepCounterUpdate", data)
        notifyListeners("stepDetectorEvent", JSObject())
        if (internalStepCount % 10 == 0L) {
            val elapsedSec = (System.currentTimeMillis() - v2SessionStartTimeMs) / 1000.0
            Log.d(TAG, "creditOneStep: βήμα #" + internalStepCount + " t=" + String.format("%.1f", elapsedSec) + "s (events επεξεργασμένα=" + algoEventCount + ")")
        }
    }

    // ============================================================================
    // FYTRA STEP ENGINE v2 — SHADOW MODE. Τίποτα εδώ δεν καλεί notifyListeners ούτε αγγίζει internalStepCount.
    // ============================================================================

    // ΝΕΟ: υπολογίζει την καθαρή μεταβολή πίεσης (hPa) μέσα στο πρόσφατο παράθυρο (V2_BAROMETER_TREND_WINDOW_MS).
    // Θετική τιμή = η πίεση ΜΕΙΩΘΗΚΕ (συνήθως σημαίνει ότι το κινητό ΑΝΕΒΗΚΕ σε ύψος) και αντίστροφα.
    // Επιστρέφει null αν δεν υπάρχει βαρόμετρο ή δεν υπάρχουν αρκετά δεδομένα ακόμα — ΔΙΑΓΝΩΣΤΙΚΟ ΜΟΝΟ,
    // δεν αποφασίζει τίποτα από μόνο του προς το παρόν.
    private fun v2BarometerTrend(): Double? {
        if (!hasBarometer || barometerHistory.size < 3) return null
        val nowMs = System.currentTimeMillis()
        val windowStart = nowMs - V2_BAROMETER_TREND_WINDOW_MS
        val inWindow = barometerHistory.filter { it.first >= windowStart }
        if (inWindow.size < 3) return null
        val first = inWindow.first().second
        val last = inWindow.last().second
        return (first - last).toDouble()
    }

    private fun v2PushSample(value: Double) {
        v2SignalBuffer[v2SignalBufferHead] = value
        v2SignalBufferHead = (v2SignalBufferHead + 1) % v2SignalBuffer.size
        if (v2SignalBufferCount < v2SignalBuffer.size) v2SignalBufferCount++
    }

    private fun v2GetRecentWindow(): DoubleArray {
        val n = min(v2RingBufferSize, v2SignalBufferCount)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val idx = (v2SignalBufferHead - n + i + v2SignalBuffer.size) % v2SignalBuffer.size
            out[i] = v2SignalBuffer[idx]
        }
        return out
    }

    // Robust στατιστικές (median + MAD) — πιο ανθεκτικό σε ένα μεμονωμένο ακραίο τίναγμα από ένα EMA.
    private fun v2RobustThreshold(window: DoubleArray): Double {
        if (window.isEmpty()) return V2_MIN_THRESHOLD
        val absVals = DoubleArray(window.size) { abs(window[it]) }
        absVals.sort()
        val median = absVals[absVals.size / 2]
        val deviations = DoubleArray(absVals.size) { abs(absVals[it] - median) }
        deviations.sort()
        val mad = deviations[deviations.size / 2]
        val t = median + V2_MEDIAN_MAD_K * mad
        return t.coerceIn(V2_MIN_THRESHOLD, V2_MAX_THRESHOLD)
    }

    // Autocorrelation: "υπάρχει σταθερή περιοδικότητα σαν βάδισμα εδώ μέσα;" — επιστρέφει (score[0..1], periodMs)
    private fun v2ComputePeriodicity(window: DoubleArray): Pair<Double, Long> {
        if (window.size < V2_MIN_BUFFER_SAMPLES || estimatedSampleIntervalMs <= 0) return Pair(0.0, 0L)
        val mean = window.average()
        val centered = DoubleArray(window.size) { window[it] - mean }
        val energy0 = centered.sumOf { it * it }
        if (energy0 < 1e-6) return Pair(0.0, 0L)

        val minLag = max(1, (V2_AUTOCORR_MIN_LAG_SEC * 1000.0 / estimatedSampleIntervalMs).toInt())
        val maxLag = min(centered.size - 1, (V2_AUTOCORR_MAX_LAG_SEC * 1000.0 / estimatedSampleIntervalMs).toInt())
        if (maxLag <= minLag) return Pair(0.0, 0L)

        var bestLag = -1
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until centered.size - lag) sum += centered[i] * centered[i + lag]
            val normalized = sum / energy0
            if (normalized > bestCorr) { bestCorr = normalized; bestLag = lag }
        }
        if (bestLag < 0) return Pair(0.0, 0L)
        val periodMs = (bestLag * estimatedSampleIntervalMs).toLong()
        return Pair(bestCorr.coerceIn(0.0, 1.0), periodMs)
    }

    private fun processV2Shadow(now: Long, hp: Double, hasProperWaveShape: Boolean, gyroPenalty: Double) {
        v2PushSample(hp)
        v2SamplesSinceAutocorr++
        val window = v2GetRecentWindow()

        if (v2SamplesSinceAutocorr >= V2_AUTOCORR_RECOMPUTE_EVERY_N) {
            v2SamplesSinceAutocorr = 0
            val (periodicity, periodMs) = v2ComputePeriodicity(window)
            v2LastPeriodicityScore = periodicity
            v2LastDominantPeriodMs = periodMs
        }

        val threshold = v2RobustThreshold(window)

        if (hp > threshold && !v2Rising && (now - v2LastPeakTimeMs) > V2_MIN_STEP_INTERVAL_MS) {
            v2Rising = true
            val interval = if (v2LastPeakTimeMs == 0L) -1L else (now - v2LastPeakTimeMs)
            v2LastPeakTimeMs = now
            // ΝΕΟ: κατά την "περίοδο χάρης" μετά το start(), οι βάσεις (gyro baseline κ.λπ.) ακόμα
            // προσαρμόζονται από τη γενική αρχική τιμή στο πραγματικό ήσυχο επίπεδο -> αγνοούμε υποψήφια
            // χτυπήματα εντελώς εδώ (δεν μπαίνουν καν στο pending buffer), ώστε να μην ξεκινήσει ποτέ
            // "επιβεβαιωμένο περπάτημα" πάνω σε ψευδή δεδομένα προσαρμογής.
            // ΝΕΟ: προστέθηκε ΚΑΙ το κοινό hasProperWaveShape -> το v2 είχε ξεχαστεί χωρίς αυτή την άμυνα
            // ενάντια σε απότομα, "artifact" χτυπήματα χωρίς καθαρό σχήμα κύματος. Τώρα ίδια μεταχείριση με v1.
            if (now - v2SessionStartTimeMs >= V2_STARTUP_GRACE_PERIOD_MS && hasProperWaveShape) {
                v2HandleCandidatePeak(now, interval, hp, gyroPenalty)
            } else if (!hasProperWaveShape) {
                Log.d(TAG, "processV2Shadow: peak χωρίς καθαρό ανερχόμενο zero-crossing -> αγνοείται (ίδια άμυνα με v1)")
            }
        } else if (hp < -threshold * 0.3) {
            v2Rising = false
        }
    }

    private fun v2HandleCandidatePeak(now: Long, intervalFromPrevious: Long, amplitude: Double, gyroPenalty: Double) {
        // ΝΕΟ: πλήρης καταγραφή ΚΑΘΕ υποψήφιου χτυπήματος (όχι μόνο απορρίψεις) — το "σφουγγάρι" δεδομένων
        // για τη χειροκίνητη βαθμονόμηση. Ο χρόνος (elapsedSec) είναι σε δευτερόλεπτα από την εκκίνηση της
        // καταμέτρησης, ώστε να αντιστοιχεί ΑΚΡΙΒΩΣ με το ορατό χρονόμετρο στην οθόνη (⏱ mm:ss).
        val elapsedSec = (now - v2SessionStartTimeMs) / 1000.0
        Log.d(TAG, "V2_CANDIDATE t=" + String.format("%.1f", elapsedSec) + "s amplitude=" + String.format("%.2f", amplitude) +
            " interval=" + intervalFromPrevious + "ms periodicity=" + String.format("%.2f", v2LastPeriodicityScore) +
            " gyroPenalty=" + String.format("%.2f", gyroPenalty))
        val intervalInRange = intervalFromPrevious in V2_MIN_STEP_INTERVAL_MS..V2_MAX_STEP_INTERVAL_MS
        val ratioOk = if (v2LastIntervalMs <= 0L || intervalFromPrevious <= 0L) true else {
            val ratio = intervalFromPrevious.toDouble() / v2LastIntervalMs.toDouble()
            val biggerOverSmaller = if (ratio >= 1.0) ratio else 1.0 / ratio
            biggerOverSmaller <= V2_MAX_INTERVAL_RATIO_CHANGE
        }
        // ΝΕΟ: ΣΤΑΘΜΙΣΜΕΝΗ ΒΑΘΜΟΛΟΓΙΑ αντί για "περιοδικότητα ΚΑΙ γυροσκόπιο πρέπει ΚΑΙ ΤΑ ΔΥΟ να περάσουν".
        // Ένα αδύναμο σήμα σε ΕΝΑ εργαλείο δεν απορρίπτει πλέον αυτόματα — προστίθενται σταθμισμένα.
        // Το διάστημα/αναλογία ΠΑΡΑΜΕΝΟΥΝ απόλυτες πύλες (πάνω), όπως και πριν.
        val gyroFriendliness = (1.0 - gyroPenalty).coerceIn(0.0, 1.0)
        val walkConfidence = v2LastPeriodicityScore.coerceIn(0.0, 1.0) * V2_WEIGHT_PERIODICITY + gyroFriendliness * V2_WEIGHT_GYRO
        val confidenceOk = walkConfidence >= V2_MIN_WALK_CONFIDENCE
        val passesTimingChecks = intervalFromPrevious != -1L && intervalInRange && ratioOk && confidenceOk

        // ΝΕΟ: διαγνωστικό — δείχνει ΑΚΡΙΒΩΣ ποιος έλεγχος απέρριψε το χτύπημα, όταν δεν είμαστε ήδη σε
        // επιβεβαιωμένο περπάτημα. Χωρίς αυτό, ένα αποτυχημένο shadow test (όπως το πρώτο) είναι "μαύρο κουτί".
        if (!v2IsWalkingConfirmed && !passesTimingChecks) {
            v2RejectLogCounter++
            if (v2RejectLogCounter % 8 == 0L) {
                Log.d(TAG, "V2_REJECT t=" + String.format("%.1f", elapsedSec) + "s interval=" + intervalFromPrevious + "ms inRange=" + intervalInRange +
                    " ratioOk=" + ratioOk + " walkConfidence=" + String.format("%.2f", walkConfidence) + "(ok=" + confidenceOk + ", min=" + V2_MIN_WALK_CONFIDENCE + ")" +
                    " periodicity=" + String.format("%.2f", v2LastPeriodicityScore) + " gyroPenalty=" + String.format("%.2f", gyroPenalty))
            }
        }

        if (v2IsWalkingConfirmed) {
            if (passesTimingChecks) {
                v2LastIntervalMs = intervalFromPrevious
                v2CreditOneStep()
            } else {
                v2IsWalkingConfirmed = false
                v2LastIntervalMs = -1L
                v2PendingPeaks = mutableListOf(now to amplitude)
            }
            return
        }

        if (!passesTimingChecks) {
            v2PendingPeaks = mutableListOf(now to amplitude)
            v2LastIntervalMs = -1L
            return
        }

        v2LastIntervalMs = intervalFromPrevious
        v2PendingPeaks.add(now to amplitude)
        if (v2PendingPeaks.size >= V2_REQUIRED_CONSECUTIVE) {
            val amplitudes = v2PendingPeaks.map { it.second }
            val meanAmp = amplitudes.average()
            val variance = amplitudes.sumOf { (it - meanAmp) * (it - meanAmp) } / amplitudes.size
            val coeffOfVariation = if (meanAmp > 0.0001) sqrt(variance) / meanAmp else 0.0

            if (coeffOfVariation <= MAX_AMPLITUDE_COEFF_OF_VARIATION) {
                v2IsWalkingConfirmed = true
                val toCredit = v2PendingPeaks.size
                v2PendingPeaks = mutableListOf()
                // ΝΕΟ: ΤΩΡΑ ακριβώς που επιβεβαιώνεται μια ΟΜΑΔΑ βημάτων -> καταγράφουμε την τάση πίεσης
                // του βαρομέτρου ως ΤΕΛΙΚΟ, ΔΙΑΓΝΩΣΤΙΚΟ επιβεβαιωτή (shadow μόνο, δεν αποφασίζει ακόμα τίποτα).
                val baroTrend = v2BarometerTrend()
                Log.d(TAG, "V2_BOUT_CONFIRMED t=" + String.format("%.1f", (now - v2SessionStartTimeMs) / 1000.0) + "s steps=" + toCredit +
                    " baroTrendHpa=" + (if (baroTrend != null) String.format("%.4f", baroTrend) else "n/a (χωρίς βαρόμετρο/δεδομένα)"))
                repeat(toCredit) { v2CreditOneStep() }
            } else {
                v2PendingPeaks = mutableListOf(v2PendingPeaks.last())
            }
        }
    }

    private fun v2CreditOneStep() {
        v2InternalStepCount++
        if (v2InternalStepCount % V2_LOG_EVERY_N_LEGACY_STEPS == 0L || internalStepCount % V2_LOG_EVERY_N_LEGACY_STEPS == 0L) {
            val elapsedSec = (System.currentTimeMillis() - v2SessionStartTimeMs) / 1000.0
            val baroTrend = v2BarometerTrend()
            Log.d(TAG, "V2_SHADOW_COMPARE t=" + String.format("%.1f", elapsedSec) + "s legacySteps=" + internalStepCount + " v2Steps=" + v2InternalStepCount +
                " diff=" + (v2InternalStepCount - internalStepCount) +
                " estimatedHz=" + String.format("%.1f", if (estimatedSampleIntervalMs > 0) 1000.0 / estimatedSampleIntervalMs else 0.0) +
                " periodicity=" + String.format("%.2f", v2LastPeriodicityScore) +
                " dominantPeriodMs=" + v2LastDominantPeriodMs +
                " gyroRms=" + String.format("%.3f", latestGyroRms) + " gyroBaseline=" + String.format("%.3f", gyroBaselineRms) +
                " baroTrendHpa=" + (if (baroTrend != null) String.format("%.4f", baroTrend) else "n/a"))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
