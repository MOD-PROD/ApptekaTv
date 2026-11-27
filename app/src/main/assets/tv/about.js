// Appteka TV — tuile « À propos » (Gonzo 26/11)
// Clic sur le logo : tout l'écran tombe (gravité) et RESTE en bas ; une tuile s'ouvre
// AU-DESSUS des éléments tombés, sans les recouvrir. Elle affiche la version installée,
// vérifie le canal choisi dans Paramètres › Mise à jour (Stable / Bêta / Test) et, si
// une version plus récente existe, la télécharge et lance l'installation Android.
// Retour (ou « Fermer ») referme la tuile et l'écran revient.

window.TvAbout = (function () {
    const CHANNEL_LABELS = { stable: 'Stable', beta: 'Bêta', test: 'Test' };
    let tile = null;
    let gravity = null;
    let state = { state: 'idle' };
    let returnFocus = null;
    let renderedState = null;

    function android(fn, ...args) {
        if (window.Android && typeof window.Android[fn] === 'function') return window.Android[fn](...args.map(String));
        return undefined;
    }
    function esc(s) {
        return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }
    function channel() {
        const c = android('getUpdateChannel');
        return CHANNEL_LABELS[c] ? c : 'stable';
    }

    function isOpen() { return !!tile; }

    function open() {
        if (tile || gravity) return;
        returnFocus = document.activeElement;
        state = { state: 'idle' };
        const handle = window.playGravitySplash && window.playGravitySplash(true, { stay: true, onLanded: (layer, pieces) => show(pieces) });
        if (handle) { gravity = handle; window.addEventListener('keydown', onKey, true); }
        else show([]);   // pas d'animation possible : la tuile s'ouvre quand même
    }

    function show(pieces) {
        renderedState = null;
        tile = document.createElement('div');
        tile.className = 'about-tile modal-box glass';
        tile.setAttribute('role', 'dialog');
        tile.setAttribute('aria-label', 'À propos');
        document.body.appendChild(tile);
        if (!gravity) window.addEventListener('keydown', onKey, true);
        place(pieces);
        render();
    }

    // La tuile se pose juste au-dessus du plus haut élément tombé.
    function place(pieces) {
        const margin = 18;
        let floor = innerHeight - margin;
        for (const p of pieces) {
            const r = p.getBoundingClientRect();
            if (r.width > 0 && r.height > 0 && r.top < floor) floor = r.top;
        }
        const bottom = Math.max(margin, innerHeight - floor + margin);
        Object.assign(tile.style, { bottom: bottom + 'px', maxHeight: Math.max(160, innerHeight - bottom - margin) + 'px' });
    }

    function check() {
        if (!window.Android || typeof window.Android.aboutCheckUpdate !== 'function') {
            state = { state: 'error', phase: 'check', code: 'hors appli' };
            render();
            return;
        }
        state = { state: 'checking', channel: channel() };
        render();
        android('aboutCheckUpdate');
    }

    function render() {
        if (!tile) return;
        const version = android('installedUpdateVersion') || 'indisponible';
        const ch = CHANNEL_LABELS[state.channel] || CHANNEL_LABELS[channel()];
        const s = state.state;
        if (s === 'downloading' && renderedState === s) {
            const percent = Math.max(0, Math.min(100, Number(state.progress) || 0));
            const bar = tile.querySelector('.about-bar');
            const statusNode = tile.querySelector('.about-status');
            const counter = tile.querySelector('[data-act="cancel"] span');
            if (bar && statusNode && counter) {
                bar.style.width = percent + '%';
                statusNode.textContent = 'Téléchargement… ' + percent + ' %';
                counter.textContent = percent + '%';
                return;
            }
        }
        let status = '';
        let buttons = [];
        let progress = '';
        if (s === 'idle') { status = 'Rechercher une mise à jour'; buttons = [['check', 'Rechercher une mise à jour']]; }
        else if (s === 'checking') { status = "Recherche d'une mise à jour…"; buttons = [['wait', 'Recherche en cours']]; }
        else if (s === 'uptodate') { status = 'Appteka TV est à jour.'; buttons = [['check', 'Rechercher à nouveau']]; }
        else if (s === 'available') {
            status = `Nouvelle version disponible : ${state.versionName} (${state.versionCode})`;
            buttons = [['install', 'Télécharger et installer']];
        } else if (s === 'downloading') {
            status = `Téléchargement… ${state.progress || 0} %`;
            progress = `<div class="about-progress"><div class="about-bar" style="width:${Math.max(0, Math.min(100, state.progress || 0))}%"></div></div>`;
            buttons = [['cancel', 'Annuler']];
        } else if (s === 'installing') { status = "Confirme l'installation dans la fenêtre Android."; buttons = [['install', 'Télécharger et installer']]; }
        else if (s === 'permission') { status = "Autorise l'installation d'applications pour Appteka TV, puis réessaie."; buttons = [['install', 'Réessayer']]; }
        else if (s === 'busy') { status = 'Une recherche est déjà en cours.'; buttons = [['check', 'Réessayer']]; }
        else if (s === 'error') {
            status = state.phase === 'download' ? `Téléchargement impossible (${state.code})` : `Vérification impossible (${state.code})`;
            buttons = [[state.phase === 'download' ? 'install' : 'check', 'Réessayer']];
        }
        // La touche Retour ferme la tuile ; aucun bouton de fermeture supplémentaire.
        // Même état (progression) : le focus reste sur le même bouton ; nouvel état : bouton principal.
        const focusedAct = s === renderedState && tile.contains(document.activeElement) ? document.activeElement.dataset.act : null;
        renderedState = s;
        tile.innerHTML = `
            <img class="about-logo" src="logo-store.webp" alt="Appteka TV Store">
            <div class="about-line">Version installée : ${esc(version)}</div>
            <div class="about-line">Canal de mise à jour : ${esc(ch)}</div>
            <div class="about-status ${s === 'error' ? 'err' : ''}" aria-live="polite">${esc(status)}</div>
            ${progress}
            <div class="modal-row about-actions" style="justify-content:center">
                ${buttons.map(([act, label, cls]) => { const round = true; const glyph = act === "close" ? `<svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>` : `<svg viewBox="0 0 100 100" width="60" height="60" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" d="M51.2 41.1L54 43.5V59L59.7 53.8L63.1 56.1L62.7 57.9L51.5 68.2L39.6 57L42.4 53.7L48.7 59V43.2Z M30.7 38.1V68.1Q31 72 38.6 72.3V82Q39 86 43 86Q47.5 86 47.5 82V72.3H53.8V82Q54 86 58 86Q63 86 63 82V72.3Q71 72 71 68V38.1Z M76.9 37.4Q72.5 37.4 72.5 42V61Q72.5 65 76.9 65Q81.3 65 81.3 61V42Q81.3 37.4 76.9 37.4Z M24.5 37.4Q20.1 37.4 20.1 42V61Q20.1 65 24.5 65Q29 65 29 61V42Q29 37.4 24.5 37.4Z M60 26A1.8 1.8 0 1 0 60 29.6A1.8 1.8 0 1 0 60 26Z M41.6 26A1.8 1.8 0 1 0 41.6 29.6A1.8 1.8 0 1 0 41.6 26Z M30.7 36.4H70.8Q70 26 60.7 20.5L63.8 14.9L62.6 14.4L59.4 19.9Q50 16 42 19.9L38.8 14.3L37.7 15.1L40.8 20.4Q32 26 30.7 36.4Z M97 15.8L80.6 13.8Q77 14 77.7 18L78.5 32.3Q81 36 85.1 31.5V27.6A42 42 0 0 1 34 88.6Q28 90 32 95A49 49 0 0 0 90.9 22.3H95.8Q101 20 97 15.8Z M68.1 4.2A49 49 0 0 0 9.4 77H4.5Q-1 80 4 84L19.8 85.6Q23 85 22.5 81L22 67.3Q18 62 15.4 67.2L15.2 71.5A42 42 0 0 1 67 10.6Q72 8 68.1 4.2Z"/></svg>`; const icon = act === "cancel" ? `<span>${Math.max(0, Math.min(100, state.progress || 0))}%</span>` : glyph; return `<button type="button" class="btn focusable ${cls || ''}" data-act="${act}" aria-label="${esc(label)}" title="${esc(label)}" ${round ? 'style="border-radius:50%;width:64px;height:64px;min-width:64px;max-width:64px;flex:0 0 64px;padding:0;display:inline-flex;align-items:center;justify-content:center"' : ""}>${icon}</button>`; }).join('')}
            </div>`;
        tile.querySelectorAll('button').forEach(b => b.addEventListener('click', () => act(b.dataset.act)));
        const again = focusedAct && tile.querySelector(`[data-act="${focusedAct}"]`);
        (again || tile.querySelector('.btn.primary') || tile.querySelector('button'))?.focus();
    }

    function act(a) {
        if (a === 'close') close();
        else if (a === 'check' && state.state !== 'checking' && state.state !== 'downloading') check();
        else if (a === 'install' && ['available', 'permission', 'error', 'installing'].includes(state.state)) { state = { state: 'downloading', progress: 0 }; render(); android('aboutInstallUpdate'); }
        else if (a === 'cancel') android('aboutCancelUpdate');
    }

    function onKey(e) {
        // La tuile garde toutes les touches tant qu'elle est ouverte (même pendant la chute).
        e.stopImmediatePropagation();
        if (e.key === 'Escape' || e.key === 'Backspace') { e.preventDefault(); close(); return; }
        if (!tile) { e.preventDefault(); return; }
        const items = Array.from(tile.querySelectorAll('button'));
        const i = items.indexOf(document.activeElement);
        if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); (items[i + 1] || items[0]).focus(); }
        else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); (items[i - 1] || items[items.length - 1]).focus(); }
        else if (e.key === 'Enter') { e.preventDefault(); if (!e.repeat && i >= 0) act(items[i].dataset.act); }
    }

    /** Ferme la tuile et fait revenir l'écran. Renvoie true si quelque chose était ouvert. */
    function close() {
        if (!tile && !gravity) return false;
        if (state.state === 'downloading') android('aboutCancelUpdate');
        window.removeEventListener('keydown', onKey, true);
        if (tile) { tile.remove(); tile = null; }
        if (gravity) { gravity.close(); gravity = null; }
        state = { state: 'idle' };
        const back = returnFocus;
        returnFocus = null;
        setTimeout(() => { if (back && back.isConnected && back.focus) back.focus(); }, 300);
        return true;
    }

    // Réponses d'Android (UpdateController.checkForTile / installFromTile)
    window.onSelfUpdateState = function (json) {
        let next;
        try { next = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
        if (!next || !tile) return;
        if (next.state === 'available' && next.versionName == null && state.versionName) next.versionName = state.versionName;
        state = Object.assign({ channel: state.channel }, next);
        render();
    };

    return { open, close, isOpen };
})();
