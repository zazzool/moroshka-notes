const cacheName = 'moroshka-shell-v1'
const shell = ['/', '/app.js', '/style.css', '/icon.png']
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

