package de.klackwerk.svenager

import groovy.xml.XmlUtil

/**
 * Public demo page for kiosk devices: shows that the device reports to this
 * Svenager instance and when it was last heard from. Looked up by the
 * unguessable device UUID; exposes nothing but hostname and freshness.
 */
class KioskDemoController {

    static allowedMethods = [show: 'GET', status: 'GET', ping: 'GET']

    /** 1x1 transparent PNG for image-based reachability probes. */
    static final byte[] PING_PNG =
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
                    .decodeBase64()

    CheckinService checkinService

    /**
     * Reachability probe for kiosk pages: loading an image works from any
     * origin (including file://) without CORS, so it is the most robust
     * "is the server up" signal a kiosk browser can use.
     */
    def ping() {
        response.setHeader('Access-Control-Allow-Origin', '*')
        response.setHeader('Cache-Control', 'no-store')
        response.contentType = 'image/png'
        response.outputStream << PING_PNG
        response.outputStream.flush()
    }

    /**
     * Machine-readable status for the kiosk's local page. The page lives at
     * file:// on the device, so cross-origin access must be allowed — it
     * exposes nothing beyond the public demo page.
     */
    def status(String id) {
        response.setHeader('Access-Control-Allow-Origin', '*')
        Device device = Device.findByUuid(id)
        if (device == null) {
            response.status = 404
            render([error: 'unknown device'] as grails.converters.JSON)
            return
        }
        render([
                hostname   : device.hostname,
                online     : checkinService.isOnline(device),
                lastContact: device.lastContactAt ? relative(device.lastContactAt) : 'never',
        ] as grails.converters.JSON)
    }

    def show(String id) {
        Device device = Device.findByUuid(id)
        if (device == null) {
            response.status = 404
            render(text: page('Unknown device', 'This device is not enrolled here.', false), contentType: 'text/html', encoding: 'UTF-8')
            return
        }
        boolean online = checkinService.isOnline(device)
        String contact = device.lastContactAt ? relative(device.lastContactAt) : 'never'
        render(text: page(XmlUtil.escapeXml(device.hostname ?: 'device'),
                "Last contact: ${XmlUtil.escapeXml(contact)}".toString(), online),
                contentType: 'text/html', encoding: 'UTF-8')
    }

    /** Coarse buckets so the kiosk text rarely changes (no visible repaints). */
    private static String relative(Date date) {
        long seconds = Math.max(0, (System.currentTimeMillis() - date.time).intdiv(1000L))
        if (seconds < 90) return 'just now'
        if (seconds < 3600) return "${seconds.intdiv(60)} minutes ago"
        "${seconds.intdiv(3600)} hours ago"
    }

    /**
     * Dependency-free page sized for a kiosk screen. Refreshes its data in
     * place via fetch — a full page reload would flash on kiosk browsers.
     */
    private static String page(String title, String detail, boolean online) {
        """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Svenager</title>
<style>
  body { margin: 0; min-height: 100vh; display: flex; flex-direction: column;
         align-items: center; justify-content: center; gap: 1.5rem;
         background: #16181d; color: #f4f6fb; font-family: system-ui, sans-serif; }
  .logo { font-size: 4rem; font-weight: 700; letter-spacing: 0.35em; }
  .badge { padding: 0.4rem 1.2rem; border-radius: 2rem; font-size: 1.3rem; }
  .badge.online { background: #1a7f37; }
  .badge.offline { background: #8a2a2a; }
  h1 { margin: 0; font-size: 2.2rem; font-weight: 500; }
  p { margin: 0; font-size: 1.4rem; color: #9aa4b5; }
</style>
</head>
<body>
  <div class="logo">SVENAGER</div>
  <h1>${title}</h1>
  <span class="badge ${online ? 'online' : 'offline'}">${online ? 'online' : 'offline'}</span>
  <p class="detail">${detail}</p>
  <p>This device is enrolled in Svenager fleet management.</p>
  <script>
    setInterval(async () => {
      try {
        const res = await fetch(location.href, { cache: 'no-store' })
        if (!res.ok) return
        const doc = new DOMParser().parseFromString(await res.text(), 'text/html')
        for (const sel of ['h1', '.badge', '.detail']) {
          const from = doc.querySelector(sel)
          const to = document.querySelector(sel)
          // Touch the DOM only on real changes — every write repaints on
          // low-power kiosk browsers.
          if (from && to && to.textContent !== from.textContent) {
            to.textContent = from.textContent
          }
          if (from && to && to.className !== from.className) {
            to.className = from.className
          }
        }
      } catch (ignored) { /* offline blip — keep the last known state */ }
    }, 15000)
  </script>
</body>
</html>
"""
    }
}
