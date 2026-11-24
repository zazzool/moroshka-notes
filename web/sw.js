const cacheName = 'moroshka-shell-v4'
const shell = ['/', '/app.js', '/backup.mjs', '/style.css', '/icon.png',
  '/assets/cloudberry.webp', '/assets/hero-day.webp', '/assets/hero-night.webp', '/assets/ideas.webp', '/assets/knowledge.webp', '/assets/search.webp', '/assets/balance.webp', '/assets/manrope.ttf', '/assets/manrope-OFL.txt', '/assets/note.svg', '/assets/checkbox.svg', '/assets/search.svg', '/assets/link.svg', '/assets/sparkles.svg', '/assets/folder.svg'
]
self.addEventListener('install', event => {
  event.waitUntil(caches.open(cacheName).then(cache => cache.addAll(shell)))
})
self.addEventListener('activate', event => {
  event.waitUntil(Promise.all([
    caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith('moroshka-shell-') && k !== cacheName).map(k => caches.delete(k)))),
    self.clients.claim()
  ]))
})
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'GET' || url.origin !== self.location.origin || !shell.includes(url.pathname)) return
  event.respondWith(fetch(event.request).catch(() => caches.open(cacheName).then(cache => cache.match(url.pathname))))
})

