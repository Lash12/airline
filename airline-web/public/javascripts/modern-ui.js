/* Modern UI toggle — persists in localStorage under 'uiMode' ('modern'|'classic') */

(function () {
    var KEY = 'uiMode';

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
    }

    function toggleMode() {
        var current = localStorage.getItem(KEY) || 'classic';
        var next = current === 'modern' ? 'classic' : 'modern';
        localStorage.setItem(KEY, next);
        applyMode(next);
    }

    /* Apply stored preference before first paint */
    applyMode(localStorage.getItem(KEY) || 'classic');

    /* Wire up button once DOM is ready */
    document.addEventListener('DOMContentLoaded', function () {
        var btn = document.getElementById('uiModeToggle');
        if (btn) {
            btn.addEventListener('click', toggleMode);
            /* Sync button label to current state */
            applyMode(localStorage.getItem(KEY) || 'classic');
        }
    });

    /* Expose for console access */
    window.toggleModernUI = toggleMode;
    window.setModernUI = function(enable) {
        var mode = enable ? 'modern' : 'classic';
        localStorage.setItem(KEY, mode);
        applyMode(mode);
    };
})();
