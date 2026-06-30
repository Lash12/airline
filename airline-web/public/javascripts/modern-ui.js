/* Modern UI toggle — persists in localStorage under 'uiMode' ('modern'|'classic') */

(function () {
    var KEY = 'uiMode';

    function syncMapTheme(mode) {
        if (window.AirlineMap && typeof AirlineMap.updateMapStyle === 'function') {
            var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
            AirlineMap.updateMapStyle(mode === 'modern' && !isDark ? 'light' : 'dark');
        }
    }

    function applyMode(mode) {
        if (mode === 'modern') {
            document.documentElement.classList.add('ui-modern');
        } else {
            document.documentElement.classList.remove('ui-modern');
        }
        var isModern = mode === 'modern';
        var label = isModern ? '&#10024; Modern &#10003;' : '&#10024; Modern';
        var title = isModern ? 'Switch to Classic UI' : 'Switch to Modern UI';
        ['uiModeToggle', 'uiModeToggleMobile'].forEach(function(id) {
            var btn = document.getElementById(id);
            if (btn) {
                btn.innerHTML = label;
                btn.title = title;
                btn.setAttribute('aria-pressed', isModern ? 'true' : 'false');
            }
        });

        /* Sync map tile theme */
        syncMapTheme(mode);

        /* Re-theme any active charts */
        if (window.reapplyChartThemes) reapplyChartThemes();
    }

    function toggleMode() {
        var current = localStorage.getItem(KEY) || 'classic';
        var next = current === 'modern' ? 'classic' : 'modern';
        localStorage.setItem(KEY, next);
        applyMode(next);
    }

    /* Apply stored preference before first paint */
    applyMode(localStorage.getItem(KEY) || 'classic');

    /* Wire up button + canvas observer once DOM is ready */
    document.addEventListener('DOMContentLoaded', function () {
        var btn = document.getElementById('uiModeToggle');
        if (btn) {
            btn.addEventListener('click', toggleMode);
            applyMode(localStorage.getItem(KEY) || 'classic');
        }

        /* Canvas slide-in: watch for .canvas elements becoming visible */
        if (window.MutationObserver) {
            var entering = false;
            var observer = new MutationObserver(function(mutations) {
                if (!document.documentElement.classList.contains('ui-modern')) return;
                mutations.forEach(function(mutation) {
                    var el = mutation.target;
                    if (!el.classList || !el.classList.contains('canvas')) return;
                    var display = el.style.display;
                    if (display && display !== 'none') {
                        el.classList.remove('m-canvas-entering');
                        void el.offsetWidth; /* force reflow to restart animation */
                        el.classList.add('m-canvas-entering');
                        var t = setTimeout(function() { el.classList.remove('m-canvas-entering'); }, 360);
                    }
                });
            });
            document.querySelectorAll('.canvas').forEach(function(c) {
                observer.observe(c, { attributes: true, attributeFilter: ['style'] });
            });
        }

        /* Re-sync map once AirlineMap is available (loaded after DOMContentLoaded) */
        var mapSyncInterval = setInterval(function() {
            if (window.AirlineMap) {
                clearInterval(mapSyncInterval);
                syncMapTheme(localStorage.getItem(KEY) || 'classic');
            }
        }, 500);
    });

    /* Expose for console access */
    window.toggleModernUI = toggleMode;
    window.setModernUI = function(enable) {
        var mode = enable ? 'modern' : 'classic';
        localStorage.setItem(KEY, mode);
        applyMode(mode);
    };
    /* Allow settings.js to re-sync map when dark mode toggles */
    window.syncModernMapTheme = function() {
        syncMapTheme(localStorage.getItem(KEY) || 'classic');
    };
})();
