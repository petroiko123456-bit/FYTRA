// ============================================================================
// FYTRA Service Worker — ΕΠΙΛΟΓΗ Α: βελτίωση αξιοπιστίας ειδοποιήσεων στο background
// (χωρίς πλήρες server-side push, βλ. σχετική συζήτηση/περιορισμούς στο app).
//
// ΤΙ ΚΑΝΕΙ:
// 1. Επιτρέπει την εγκατάσταση της εφαρμογής ως PWA (Προσθήκη στην αρχική οθόνη) —
//    τα εγκατεστημένα PWA παραμένουν "ζωντανά" στο background πολύ περισσότερο από ένα
//    απλό browser tab, άρα οι ειδοποιήσεις έχουν πολύ μεγαλύτερη πιθανότητα να φανούν
//    ακόμα κι όταν ο χρήστης έχει βγει από την εφαρμογή (αλλά όχι την έχει κλείσει εντελώς
//    για ώρες/επανεκκίνηση κινητού — για αυτό χρειάζεται πραγματικό server push, ΕΠΙΛΟΓΗ Β).
// 2. Εμφανίζει τις ειδοποιήσεις μέσω self.registration.showNotification() αντί για το
//    απλό `new Notification()` της σελίδας — πιο αξιόπιστο σε Android/Chrome όταν το tab
//    δεν είναι στο προσκήνιο.
// 3. Cache-first για το ίδιο το index.html ώστε να ανοίγει έστω και χωρίς σταθερό internet
//    μετά την πρώτη επίσκεψη (bonus: βασικό offline-shell).
//
// ΣΗΜΑΝΤΙΚΟ: οι Service Workers ΔΕΝ λειτουργούν καθόλου πάνω από file:// (browser security) —
// χρειάζεται πραγματικό https:// ή http://localhost για να ενεργοποιηθεί οτιδήποτε εδώ.

const CACHE_NAME = 'fytra-shell-v2';
const SHELL_FILES = ['./', './index.html'];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_FILES)).catch(() => {})
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// NETWORK-FIRST για το βασικό αρχείο -> πάντα προτεραιότητα στο ΠΙΟ ΠΡΟΣΦΑΤΟ index.html από το δίκτυο.
// Η cache χρησιμοποιείται ΜΟΝΟ ως fallback αν δεν υπάρχει καθόλου σύνδεση (βασικό offline-shell).
// (Το προηγούμενο cache-first σχέδιο σέρβιρε παλιές εκδόσεις της εφαρμογής μετά από κάθε ενημέρωση —
// διορθώθηκε ρητά μετά από αναφορά χρήστη ότι δεν έβλεπε τις αλλαγές.)
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)).catch(() => {});
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});

// Η κύρια σελίδα στέλνει postMessage({type:'SHOW_NOTIFICATION', title, body}) όποτε μια
// υπενθύμιση σημείωσης "πυροδοτείται" (βλ. notesLocalNotify στο index.html) — το SW εμφανίζει
// την ειδοποίηση μέσω showNotification(), πιο αξιόπιστο από το page-level Notification API.
self.addEventListener('message', (event) => {
  const data = event.data || {};
  if (data.type === 'SHOW_NOTIFICATION') {
    self.registration.showNotification(data.title || 'FYTRA', {
      body: data.body || '',
      icon: 'icon-192.png',
      badge: 'icon-192.png',
      tag: data.tag || undefined,
    });
  }
});

// Κλικ πάνω σε ειδοποίηση -> φέρνει στο προσκήνιο ένα ήδη ανοιχτό tab της εφαρμογής, ή ανοίγει νέο.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientsArr) => {
      for (const c of clientsArr) { if ('focus' in c) return c.focus(); }
      if (self.clients.openWindow) return self.clients.openWindow('./index.html');
    })
  );
});

// Periodic Background Sync — υποστηρίζεται ΜΟΝΟ σε εγκατεστημένα PWA σε Chrome/Android (Chromium-based),
// ΟΧΙ σε Safari/iOS ή απλό browser tab. Όπου δουλεύει, ζητάει από ανοιχτά tabs να ελέγξουν υπενθυμίσεις
// ακόμα κι αν ο χρήστης δεν έχει ανοίξει την εφαρμογή πρόσφατα. Χωρίς αυτό, ισχύει ό,τι ίσχυε πριν
// (έλεγχος μόνο όσο η σελίδα είναι ζωντανή στη μνήμη).
self.addEventListener('periodicsync', (event) => {
  if (event.tag === 'fytra-notes-reminder-check') {
    event.waitUntil(
      self.clients.matchAll({ includeUncontrolled: true }).then((clientsArr) => {
        clientsArr.forEach((c) => c.postMessage({ type: 'CHECK_REMINDERS' }));
      })
    );
  }
});
