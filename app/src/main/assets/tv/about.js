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
        buttons.push(['close', 'Fermer']);
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
                ${buttons.map(([act, label, cls]) => { const round = true; const glyph = act === "close" ? `<svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>` : `<svg viewBox="0 0 48 48" width="56" height="56" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M42 14A20 20 0 1 0 44 26M42 4v10H32"/><path d="M9 16l10-5 10 5-10 5zM9 16v13l10 5V21M29 16v5"/><path d="M24 28a8 8 0 0 1 16 0v9H24zM26 21l-2-3M38 21l2-3M29 26h.1M35 26h.1M32 30v5m-3-3 3 3 3-3"/></svg>`; const icon = act === "install" ? `<svg viewBox="0 0 48 48" width="56" height="56" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M24 5v27M12 20l12 12 12-12M6 34v9h36v-9"/></svg>` : act === "cancel" ? `<span>${Math.max(0, Math.min(100, state.progress || 0))}%</span>` : glyph; return `<button type="button" class="btn focusable ${cls || ''}" data-act="${act}" aria-label="${esc(label)}" title="${esc(label)}" ${round ? 'style="border-radius:50%;width:64px;height:64px;min-width:64px;max-width:64px;flex:0 0 64px;padding:0;display:inline-flex;align-items:center;justify-content:center"' : ""}>${icon}</button>`; }).join('')}
            </div>`;
        tile.querySelectorAll('button').forEach(b => b.addEventListener('click', () => act(b.dataset.act)));
        const again = focusedAct && tile.querySelector(`[data-act="${focusedAct}"]`);
        (again || tile.querySelector('.btn.primary') || tile.querySelector('button')).focus();
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
