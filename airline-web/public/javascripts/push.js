(function() {
    var PUSH_DEBUG_VERSION = 'push-20260620-0102'
    var pushState = {
        config: null,
        registration: null,
        subscription: null,
        status: null,
        error: null,
        saving: false,
        lastAction: null,
        attemptedSubscribe: false,
        initialized: false
    }

    function urlBase64ToUint8Array(base64String) {
        var padding = '='.repeat((4 - base64String.length % 4) % 4)
        var base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
        var rawData = window.atob(base64)
        var outputArray = new Uint8Array(rawData.length)
        for (var i = 0; i < rawData.length; ++i) {
            outputArray[i] = rawData.charCodeAt(i)
        }
        return outputArray
    }

    async function loadPushConfig() {
        const response = await fetch('/push-config', { credentials: 'same-origin' })
        if (!response.ok) throw new Error('Push config failed: ' + response.status)
        return response.json()
    }

    function pushUnavailableReason() {
        if (!pushState.config || !pushState.config.enabled) return 'disabled'
        if (!pushState.config.configured || !pushState.config.vapidPublicKey) return 'not-configured'
        if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) return 'unsupported'
        if (!window.isSecureContext) return 'insecure-context'
        return null
    }

    async function registerServiceWorker() {
        var reason = pushUnavailableReason()
        if (reason) return { registered: false, reason: reason }
        await navigator.serviceWorker.register('/sw.js')
        pushState.lastAction = 'wait-service-worker-ready'
        updatePushDebugState()
        pushState.registration = await navigator.serviceWorker.ready
        pushState.subscription = await pushState.registration.pushManager.getSubscription()
        return { registered: true }
    }

    async function loadSubscriptionStatus() {
        if (typeof activeAirline === 'undefined' || !activeAirline || !pushState.config || !pushState.config.enabled) return null
        const response = await fetch('/airlines/' + activeAirline.id + '/push-subscription', { credentials: 'same-origin' })
        if (!response.ok) throw new Error('Subscription status failed: ' + response.status)
        pushState.status = await response.json()
        return pushState.status
    }

    function appendPushToggle() {
        var drawer = document.getElementById('notificationDrawer')
        if (!drawer || document.getElementById('pushNotificationSetting')) return

        var row = document.createElement('div')
        row.id = 'pushNotificationSetting'
        row.className = 'notification-push-setting'
        row.innerHTML =
            '<label title="Phone notifications">' +
            '<input id="pushNotificationToggle" type="checkbox"> ' +
            '<span>Phone notifications</span>' +
            '</label>' +
            '<div class="notification-push-meta">' +
            '<div id="pushNotificationStatus" class="notification-push-status"></div>' +
            '<div id="pushNotificationDetail" class="notification-push-detail"></div>' +
            '</div>'
        var list = document.getElementById('notificationList')
        drawer.insertBefore(row, list || drawer.firstChild)

        document.getElementById('pushNotificationToggle').addEventListener('change', function(e) {
            if (e.target.checked) {
                subscribeToPush().catch(function(error) {
                    console.warn('Push subscribe failed', error)
                    pushState.error = userFacingPushError(error)
                    renderPushStatus('error')
                })
            } else {
                unsubscribeFromPush().catch(function(error) {
                    console.warn('Push unsubscribe failed', error)
                    pushState.error = userFacingPushError(error)
                    renderPushStatus('error')
                })
            }
        })
    }

    function renderPushStatus(statusOverride) {
        appendPushToggle()
        var row = document.getElementById('pushNotificationSetting')
        var toggle = document.getElementById('pushNotificationToggle')
        var label = document.getElementById('pushNotificationStatus')
        var detail = document.getElementById('pushNotificationDetail')
        if (!toggle || !label) return

        var reason = pushUnavailableReason()
        if (reason) {
            if (row) {
                row.style.display = reason === 'disabled' || reason === 'not-configured' ? 'none' : 'flex'
            }
            toggle.checked = false
            toggle.disabled = true
            label.textContent = reason === 'disabled' ? 'Off on this server' :
                reason === 'insecure-context' ? 'Requires HTTPS' :
                reason === 'not-configured' ? 'Needs VAPID keys' : 'Unsupported browser'
            renderPushDiagnostic(detail)
            return
        }

        if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
            if (row) row.style.display = 'flex'
            toggle.checked = false
            toggle.disabled = true
            label.textContent = "Blocked - enable in your browser's site settings, then reload"
            renderPushDiagnostic(detail)
            return
        }

        if (row) {
            row.style.display = 'flex'
        }
        toggle.disabled = false
        var saving = statusOverride === 'saving' || (!statusOverride && pushState.saving)
        toggle.checked = saving ? true : !!pushState.subscription || !!(pushState.status && pushState.status.subscribed)
        label.textContent = saving ? 'Saving...' :
            statusOverride === 'error' ? pushState.error || 'Could not enable' :
            toggle.checked ? 'Enabled on this device' :
            pushState.attemptedSubscribe ? 'Off - not saved' : 'Off'
        renderPushDiagnostic(detail)
        updatePushDebugState()
    }

    async function subscribeToPush() {
        pushState.error = null
        pushState.saving = true
        pushState.attemptedSubscribe = true
        pushState.lastAction = 'subscribe-start'
        updatePushDebugState()
        renderPushStatus('saving')
        try {
            if (!pushState.registration) {
                pushState.lastAction = 'register-service-worker'
                updatePushDebugState()
                var registrationState = await registerServiceWorker()
                if (!registrationState.registered) throw new Error(registrationState.reason || 'Service worker unavailable')
            }
            if (Notification.permission !== 'granted') {
                pushState.lastAction = 'request-permission'
                updatePushDebugState()
                var permission = await Notification.requestPermission()
                if (permission !== 'granted') throw new Error('Notification permission denied')
            }
            pushState.lastAction = 'read-browser-subscription'
            updatePushDebugState()
            pushState.subscription = await pushState.registration.pushManager.getSubscription()
            if (!pushState.subscription) {
                pushState.lastAction = 'create-browser-subscription'
                updatePushDebugState()
                pushState.subscription = await pushState.registration.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: urlBase64ToUint8Array(pushState.config.vapidPublicKey)
                })
            }
            var subscriptionJson = serializePushSubscription(pushState.subscription)
            pushState.lastAction = 'save-server-subscription'
            updatePushDebugState()
            const response = await fetch('/airlines/' + activeAirline.id + '/push-subscription', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(subscriptionJson)
            })
            if (!response.ok) throw new Error('Subscription save failed: ' + response.status + ' ' + (await response.text()).slice(0, 120))
            pushState.lastAction = 'check-server-subscription'
            updatePushDebugState()
            await loadSubscriptionStatus()
            pushState.lastAction = 'subscribe-success'
        } finally {
            pushState.saving = false
            updatePushDebugState()
        }
        renderPushStatus()
    }

    async function unsubscribeFromPush() {
        pushState.error = null
        pushState.saving = true
        pushState.lastAction = 'unsubscribe-start'
        updatePushDebugState()
        renderPushStatus('saving')
        try {
            var endpoint = pushState.subscription && pushState.subscription.endpoint
            if (pushState.subscription) {
                await pushState.subscription.unsubscribe()
            }
            pushState.subscription = null
            await fetch('/airlines/' + activeAirline.id + '/push-subscription', {
                method: 'DELETE',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(endpoint ? { endpoint: endpoint } : {})
            })
            await loadSubscriptionStatus()
            pushState.lastAction = 'unsubscribe-success'
            pushState.attemptedSubscribe = false
        } finally {
            pushState.saving = false
            updatePushDebugState()
        }
        renderPushStatus()
    }

    function serializePushSubscription(subscription) {
        var json = subscription && typeof subscription.toJSON === 'function' ? subscription.toJSON() : subscription
        if (!json || !json.endpoint || !json.keys || !json.keys.p256dh || !json.keys.auth) {
            throw new Error('Browser returned an incomplete push subscription')
        }
        return json
    }

    function userFacingPushError(error) {
        var message = error && error.message ? error.message : String(error)
        window.pushLastError = message
        pushState.saving = false
        updatePushDebugState()
        if (/permission/i.test(message)) return "Blocked - enable in your browser's site settings, then reload"
        if (/insecure-context/i.test(message)) return 'Requires HTTPS'
        if (/status failed|save failed/i.test(message)) return 'Server save failed'
        if (/incomplete/i.test(message)) return 'Browser subscription failed'
        return 'Could not enable'
    }

    function updatePushDebugState() {
        window.pushDebugState = {
            version: PUSH_DEBUG_VERSION,
            saving: pushState.saving,
            lastAction: pushState.lastAction,
            attemptedSubscribe: pushState.attemptedSubscribe,
            hasConfig: !!pushState.config,
            hasRegistration: !!pushState.registration,
            hasSubscription: !!pushState.subscription,
            serverStatus: pushState.status,
            error: pushState.error,
            lastError: window.pushLastError || null,
            permission: typeof Notification !== 'undefined' ? Notification.permission : 'unavailable',
            secureContext: window.isSecureContext
        }
    }

    function debugQueryEnabled() {
        return new URLSearchParams(window.location.search).get('pushDebug') === '1'
    }

    function renderPushDiagnostic(detail) {
        if (!detail) return
        if (!debugQueryEnabled()) {
            detail.textContent = ''
            return
        }
        var serverSubscribed = pushState.status && pushState.status.subscribed
        var permission = typeof Notification !== 'undefined' ? Notification.permission : 'unavailable'
        if (!pushState.attemptedSubscribe && !pushState.error && !window.pushLastError) {
            detail.textContent = PUSH_DEBUG_VERSION
            return
        }
        detail.textContent = 'diag ' + PUSH_DEBUG_VERSION +
            ' step=' + (pushState.lastAction || 'init') +
            ' perm=' + permission +
            ' browser=' + (!!pushState.subscription) +
            ' server=' + (!!serverSubscribed) +
            (pushState.error ? ' error=' + pushState.error : '') +
            (window.pushLastError ? ' raw=' + window.pushLastError.slice(0, 80) : '')
    }

    function handlePushDeepLink() {
        var params = new URLSearchParams(window.location.search)
        var from = parseInt(params.get('planLinkFrom'))
        var to = parseInt(params.get('planLinkTo'))
        if (!from || !to) return
        var attempts = 0
        var timer = setInterval(function() {
            attempts += 1
            if (window.activeAirline && typeof window.planLink === 'function') {
                clearInterval(timer)
                window.history.replaceState(null, document.title, window.location.pathname)
                planLink(from, to)
            } else if (attempts > 60) {
                clearInterval(timer)
            }
        }, 500)
    }

    async function initPushShell() {
        if (pushState.initialized) return
        pushState.initialized = true
        try {
            pushState.config = await loadPushConfig()
            window.pushConfig = pushState.config
            window.pushRegistrationState = await registerServiceWorker()
            await loadSubscriptionStatus()
            renderPushStatus()
        } catch (e) {
            window.pushRegistrationState = { registered: false, reason: 'error' }
            console.warn('Push shell unavailable', e)
            renderPushStatus('unavailable')
        }
        updatePushDebugState()
        handlePushDeepLink()
    }

    window.initPushShell = initPushShell
    window.loadSubscriptionStatus = loadSubscriptionStatus
    window.renderPushStatus = renderPushStatus
    initPushShell()
})()
