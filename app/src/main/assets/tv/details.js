// Appteka TV - fiche application en HTML
// Remplace l'écran de détails natif : installer / mettre à jour / ouvrir /
// désinstaller, résumé IA, toutes les infos, versions, captures, apps similaires.

(function () {
    const DL_IDLE = -30, DL_AWAIT = -10, DL_STARTED = -20, DL_ERROR = -40, DL_COMPLETED = 101;

    const root = document.getElementById('details');
    const viewer = document.getElementById('viewer');
    const viewerImg = viewer.querySelector('img');

    let stack = [];          // historique des fiches ouvertes (ids)
    let data = null;         // fiche affichée
    let state = null;        // état installé / téléchargement
    let returnFocus = null;  // carte à re-sélectionner en sortant
    let confirmRisk = false; // 2e appui requis si app suspecte
    let showDesc = false;    // description masquée par défaut
    let versionInfo = {};    // id -> {abi, time} (chargé à la demande)
    let versionsRequestedFor = null;
    const vprefs = (function () {
        try { return JSON.parse((window.Android && window.Android.getPref && window.Android.getPref('versions')) || '{}') || {}; }
        catch (e) { return {}; }
    })();
    function saveVprefs() {
        try { if (window.Android && window.Android.setPref) window.Android.setPref('versions', JSON.stringify(vprefs)); } catch (e) { /* */ }
    }
    let viewerShots = [], viewerIndex = 0;

    const A = () => window.Android || {};
    const call = (fn, ...args) => { if (typeof A()[fn] === 'function') A()[fn](...args.map(String)); };

    // ------------------------------------------------------------ utilitaires

    function esc(t) {
        const d = document.createElement('div');
        d.textContent = t == null ? '' : String(t);
        return d.innerHTML;
    }

    function fmtDate(t) {
        if (!t) return '';
        const ms = t > 1e12 ? t : t * 1000;
        return new Date(ms).toLocaleDateString((window.TvI18n && window.TvI18n.locale) || 'fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    function stars(n) {
        const r = Math.round(n || 0);
        return '★'.repeat(r) + '☆'.repeat(Math.max(0, 5 - r));
    }

    function iconHtml(src, cls) {
        return src
            ? `<img class="${cls}" src="${esc(src)}" alt="" onerror="this.style.visibility='hidden'">`
            : `<div class="${cls}" style="display:flex;align-items:center;justify-content:center;font-size:56px">📺</div>`;
    }

    // ------------------------------------------------------------ ouverture

    function open(id, preview) {
        if (authorOpen()) closeAuthor(false);
        if (!isOpen()) {
            returnFocus = document.activeElement;
            stack = [];
        }
        if (stack[stack.length - 1] !== String(id)) stack.push(String(id));
        data = null;
        state = null;
        confirmRisk = false;
        showDesc = false;
        root.classList.remove('hidden');
        root.scrollTop = 0;
        root.innerHTML = preview
            ? `<div class="d-head">${iconHtml(preview.icon, 'd-icon')}<div class="d-title">
                 <div class="d-name">${esc(preview.name)}</div>
                 <div class="d-sub">Chargement de la fiche…</div></div></div>`
            : '<div class="d-loading">Chargement…</div>';
        call('openAppDetails', id);
    }

    function close() {
        root.classList.add('hidden');
        root.innerHTML = '';
        data = null;
        state = null;
        stack = [];
        call('closeDetails');
        if (returnFocus && document.body.contains(returnFocus)) returnFocus.focus({ preventScroll: true });
    }

    function isOpen() { return !root.classList.contains('hidden'); }

    // Retour : visionneuse > version précédente > fermeture
    function back() {
        if (!viewer.classList.contains('hidden')) { closeViewer(); return true; }
        if (authorOpen()) { closeAuthor(true); return true; }
        if (!isOpen()) return false;
        if (stack.length > 1) {
            stack.pop();
            const prev = stack.pop();
            open(prev);
            return true;
        }
        close();
        return true;
    }

    // ------------------------------------------------------------ rendu

    function render(keepFocus) {
        if (!data) return;
        const d = data;
        const focusedKey = keepFocus && document.activeElement && document.activeElement.dataset
            ? document.activeElement.dataset.fk : null;
        const scroll = root.scrollTop;

        const sec = d.security || {};
        const badges = [];
        if (d.category) badges.push(`<span class="badge blue">${esc(d.category)}</span>`);
        if (d.official) badges.push('<span class="badge green">✔ Développeur officiel</span>');
        if (d.openSource) badges.push('<span class="badge green">Open source</span>');
        if (d.exclusive) badges.push('<span class="badge blue">Exclusif Appteka</span>');
        if (sec.status === 'completed') {
            if (sec.verdict === 'safe') badges.push('<span class="badge green">🛡 Analyse : sûre</span>');
            else if (sec.verdict === 'suspicious') badges.push('<span class="badge orange">⚠ Analyse : suspecte</span>');
            else if (sec.verdict === 'malware') badges.push('<span class="badge red">⛔ Analyse : malveillante</span>');
        } else if (sec.status === 'pending' || sec.status === 'scanning') {
            badges.push('<span class="badge">🛡 Analyse en cours</span>');
        }

        const html = [];
        html.push(`
          <button class="close-btn back-btn mobile-only" type="button" data-action="back" aria-label="Retour">←</button>
          <div class="d-head">
            ${iconHtml(d.icon, 'd-icon')}
            <div class="d-title">
              <div class="d-name">${esc(d.name)}</div>
              <div class="d-sub">${esc(d.author || '')} · v${esc(d.version)} · ${fmtDate(d.time)}</div>
              <div class="d-badges">${badges.join('')}</div>
              <div id="d-actions" class="d-actions"></div>
              <div id="d-note"></div>
            </div>
          </div>
          <div class="d-stats">
            ${d.rating ? `<div><div class="stat-value">${Number(d.rating).toFixed(1)} ★</div><div class="stat-label">${d.rateCount} avis</div></div>` : ''}
            <div><div class="stat-value">${esc(d.downloadsText)}</div><div class="stat-label">téléchargements</div></div>
            <div><div class="stat-value">${esc(d.size)}</div><div class="stat-label">taille</div></div>
            <div><div class="stat-value">${esc(d.android || ('API ' + d.sdkVersion))}</div><div class="stat-label">Android minimum</div></div>
            ${d.favorites ? `<div><div class="stat-value">${d.favorites}</div><div class="stat-label">favoris</div></div>` : ''}
          </div>`);

        // Résumé IA
        html.push(aiBlock(d));

        if (d.whatsNew) {
            html.push(`<div class="d-block focusable" tabindex="0" data-fk="whatsnew">
                <h3>Nouveautés</h3><div class="d-text">${esc(d.whatsNew)}</div></div>`);
        }
        if (d.description && showDesc) {
            html.push(`<div class="d-block focusable" tabindex="0" data-fk="desc">
                <h3>Description</h3><div class="d-text">${esc(d.description)}</div></div>`);
        }

        if (d.screenshots && d.screenshots.length) {
            html.push(`<div class="d-block"><h3>Captures d'écran</h3><div class="shots">${
                d.screenshots.map((s, i) =>
                    `<img class="shot focusable" tabindex="0" data-fk="shot-${i}" data-shot="${i}" src="${esc(s.preview)}" alt="">`
                ).join('')}</div></div>`);
        }

        if (d.versions && d.versions.length) {
            html.push(`<div class="d-block" id="versions-block"></div>`);
        }

        if (d.scores || (d.reviews && d.reviews.length)) {
            const total = (d.scores || []).reduce((a, b) => a + b, 0) || 1;
            html.push(`<div class="d-block focusable" tabindex="0" data-fk="reviews"><h3>Avis</h3>
                ${d.scores ? `<div class="score-bars">${d.scores.map((n, i) =>
                    `<span>${5 - i}</span><div class="score-bar"><div style="width:${(n / total * 100).toFixed(0)}%"></div></div><span>${n}</span>`
                ).join('')}</div>` : ''}
                <div class="mini-row" style="justify-content:flex-start;margin:0 0 6px"><button class="mini-btn focusable" data-fk="classic-reviews" data-action="classic-reviews">Tous les avis et la discussion</button></div>
                ${(d.reviews || []).map(r => `
                  <div class="review"><span class="stars">${stars(r.score)}</span><span class="who">${esc(r.author || '')}</span>
                  ${r.text ? `<div class="txt">${esc(r.text)}</div>` : ''}</div>`).join('')}
            </div>`);
        }

        const perms = d.permissions || [];
        html.push(`<div class="d-block focusable expandable" tabindex="0" data-fk="info"><h3>Informations</h3>
            <div class="info-grid">
              <span class="k">Paquet</span><span class="v">${esc(d.package)}</span>
              <span class="k">Version</span><span class="v">${esc(d.version)} (code ${d.versionCode})</span>
              <span class="k">Android minimum</span><span class="v">${esc(d.android)} (API ${d.sdkVersion})</span>
              ${d.abi.length ? `<span class="k">Architectures</span><span class="v">${esc(d.abi.join(', '))}</span>` : ''}
              ${d.languages.length ? `<span class="k">Langues</span><span class="v">${esc(d.languages.join(', '))}</span>` : ''}
              <span class="k">Mise en ligne</span><span class="v">${fmtDate(d.time)} par ${esc(d.author || '?')}</span>
              ${d.sourceUrl ? `<span class="k">Code source</span><span class="v">${esc(d.sourceUrl)}</span>` : ''}
              <span class="k">SHA-1</span><span class="v">${esc(d.sha1)}</span>
              <span class="k">Permissions</span><span class="v d-perms">${perms.length} — OK pour les afficher</span>
            </div></div>`);

        if (d.similar && d.similar.length) {
            html.push(`<div class="d-block"><h3>Apps similaires</h3><div class="apps-row similar-row"></div></div>`);
        }

        root.innerHTML = html.join('');

        // Cartes similaires (réutilise le rendu de la page d'accueil)
        const simRow = root.querySelector('.similar-row');
        if (simRow && window.TvCards) {
            d.similar.forEach(app => {
                const card = window.TvCards.create(app);
                card.classList.add('focusable');
                card.dataset.fk = 'sim-' + app.id;
                simRow.appendChild(card);
            });
        }

        bindEvents();
        renderVersions();
        requestVersionsInfo();
        renderActions();
        root.scrollTop = scroll;

        const again = focusedKey && root.querySelector(`[data-fk="${CSS.escape(focusedKey)}"]`);
        if (again) again.focus({ preventScroll: true });
        else focusFirstAction();
    }

    function aiBlock(d) {
        const status = d.aiStatus;
        // Pas de tuile IA : le bouton Description reste accessible
        if (status === 'disabled') return d.description ? `<div class="d-block">${descToggle(d)}</div>` : '';
        let body;
        if (d.aiNote) {
            body = `<div class="d-text">${esc(d.aiNote)}</div>`;
        } else if (status === 'pending') {
            body = '<div class="d-text">⏳ Résumé en cours de génération… il s\'affichera tout seul.</div>';
        } else if (d.fileStatus === 0) {
            body = `<div class="d-text">Aucun résumé pour l'instant.</div>
                    <div class="d-actions" style="margin-top:12px">
                      <button class="btn focusable" data-fk="ai-request" data-action="ai">✨ Générer le résumé IA</button>
                    </div>`;
        } else {
            return d.description ? `<div class="d-block">${descToggle(d)}</div>` : '';
        }
        const tags = (d.aiTags || []).length
            ? `<div class="chips">${d.aiTags.map(t => `<span class="chip">#${esc(t)}</span>`).join('')}</div>` : '';
        const short = d.aiShort && d.aiNote ? `<div class="d-text" style="font-weight:600;margin-bottom:8px">${esc(d.aiShort)}</div>` : '';
        return `<div class="d-block ai-block focusable" tabindex="0" data-fk="ai"><h3><span class="ai-icon">✨</span>Résumé IA</h3>${short}${body}${tags}${descToggle(d)}</div>`;
    }

    function descToggle(d) {
        if (!d.description) return '';
        return `<div class="mini-row"><button class="mini-btn focusable" data-fk="desc-toggle" data-action="desc">${
            showDesc ? 'Masquer la description ▴' : 'Description ▾'}</button></div>`;
    }

    // ------------------------------------------------------------ versions

    function versionCompatible(v) {
        const info = versionInfo[v.id];
        if (!info || info.error) return null; // inconnu (pas encore chargé)
        return window.TvArch ? window.TvArch.isAllowed(info.abi) : true;
    }

    function fmtAbi(abi) {
        if (!abi || !abi.length) return '';
        const short = { 'arm64-v8a': 'ARM64', 'armeabi-v7a': 'ARM32', 'armeabi': 'ARM', 'x86_64': 'x86_64', 'x86': 'x86', 'universal': 'Universelle' };
        return abi.map(a => short[a] || a).join(' / ');
    }

    function renderVersions(focusKey) {
        const block = root.querySelector('#versions-block');
        if (!block || !data) return;
        const a = document.activeElement;
        const keep = focusKey || (a && block.contains(a) ? a.dataset.fk : null);
        const installedCode = state && state.installedVersionCode >= 0 ? state.installedVersionCode : null;

        let list = data.versions.slice();
        if (vprefs.recent) {
            list.sort((x, y) => {
                const tx = (versionInfo[x.id] || {}).time, ty = (versionInfo[y.id] || {}).time;
                if (tx && ty && tx !== ty) return ty - tx;
                return y.code - x.code;
            });
        }
        const total = list.length;
        const pending = list.some(v => !versionInfo[v.id]);
        if (vprefs.compat) list = list.filter(v => versionCompatible(v) !== false);
        const hidden = total - list.length;

        block.innerHTML = `
            <div class="v-head">
              <h3>Versions (${list.length}${hidden ? ' / ' + total : ''})</h3>
              <div class="v-tools">
                <button class="mini-btn focusable ${vprefs.recent ? 'on' : ''}" data-fk="v-sort" data-action="v-sort">↓ Plus récentes en haut</button>
                <button class="mini-btn focusable ${vprefs.compat ? 'on' : ''}" data-fk="v-compat" data-action="v-compat">✓ Compatibles seulement</button>
              </div>
            </div>
            ${vprefs.compat && pending ? '<div class="d-more" style="margin-bottom:8px">Vérification des architectures…</div>' : ''}
            ${hidden && vprefs.compat ? `<div class="d-more" style="margin-bottom:8px">${hidden} version(s) incompatible(s) masquée(s)</div>` : ''}
            <div class="versions">${list.map(v => {
                const info = versionInfo[v.id] || {};
                const comp = versionCompatible(v);
                const meta = [
                    info.time ? fmtDate(info.time) : '',
                    fmtAbi(info.abi),
                    'code ' + v.code, 'API ' + v.sdk, v.downloads + ' téléch.',
                ].filter(Boolean).join(' · ');
                return `
                  <div class="version focusable ${v.current ? 'current' : ''} ${installedCode === v.code ? 'installed' : ''} ${comp === false ? 'incompatible' : ''}"
                       tabindex="0" data-fk="v-${esc(v.id)}" data-version="${esc(v.id)}" data-code="${v.code}">
                    <span class="v-name">v${esc(v.name)}${v.current ? ' · affichée' : ''}</span>
                    <span class="v-meta">${esc(meta)}</span>
                  </div>`;
            }).join('')}</div>`;

        block.querySelectorAll('[data-action]').forEach(b => b.addEventListener('click', () => onAction(b.dataset.action)));
        block.querySelectorAll('.version').forEach(v => v.addEventListener('click', () => {
            if (v.dataset.version !== String(data.id)) open(v.dataset.version);
        }));
        if (keep) {
            const el = block.querySelector(`[data-fk="${CSS.escape(keep)}"]`) || block.querySelector('.focusable');
            if (el) el.focus({ preventScroll: true });
        }
    }

    function requestVersionsInfo() {
        if (!data || !data.versions || !data.versions.length) return;
        if (versionsRequestedFor === data.id) return;
        versionsRequestedFor = data.id;
        const ids = data.versions.map(v => String(v.id)).filter(id => !versionInfo[id]);
        // la version affichée est déjà connue
        versionInfo[String(data.id)] = { abi: data.abi || [], time: data.time };
        const rest = ids.filter(id => id !== String(data.id));
        if (rest.length && window.Android && window.Android.loadVersionsInfo) window.Android.loadVersionsInfo(JSON.stringify(rest));
        else renderVersions();
    }

    let versionsRenderTimer = null;
    window.onVersionInfo = function (json) {
        const v = typeof json === 'string' ? JSON.parse(json) : json;
        versionInfo[String(v.id)] = v;
        // regroupe les rafraîchissements (une réponse par version)
        clearTimeout(versionsRenderTimer);
        versionsRenderTimer = setTimeout(() => renderVersions(), 120);
    };

    // ------------------------------------------------------------ actions

    function renderActions() {
        const box = root.querySelector('#d-actions');
        const note = root.querySelector('#d-note');
        if (!box || !data) return;
        const focusedKey = document.activeElement && box.contains(document.activeElement)
            ? document.activeElement.dataset.fk : null;

        const d = data;
        const s = state || { installedVersionCode: -1, download: DL_IDLE, install: 'idle' };
        const installed = s.installedVersionCode >= 0;
        const dl = s.download;
        const downloading = dl === DL_AWAIT || dl === DL_STARTED || (dl >= 0 && dl < 100);
        const installing = s.install && s.install !== 'idle';
        const risky = d.security && d.security.status === 'completed' &&
            (d.security.verdict === 'malware' || d.security.verdict === 'suspicious');

        const btns = [];
        let progress = '';
        let noteText = '';

        if (downloading || installing) {
            let label, pct = null;
            if (installing) {
                label = s.install === 'confirming' ? 'Confirmez l\'installation à l\'écran'
                    : s.install === 'writing' ? `Préparation de l'installation ${s.installPercent || 0} %`
                    : 'Installation…';
                if (s.install === 'writing') pct = s.installPercent || 0;
            } else if (dl >= 0) {
                label = `Téléchargement ${dl} %`; pct = dl;
            } else {
                label = 'Démarrage du téléchargement…';
            }
            progress = `<div class="d-progress"><div class="d-progress-label">${label}</div>
                <div class="d-progress-bar"><div class="d-progress-fill ${pct == null ? 'indeterminate' : ''}"
                style="${pct == null ? '' : 'width:' + pct + '%'}"></div></div></div>`;
            btns.push(['cancel', 'Annuler', 'danger']);
        } else {
            const installLabel = risky && confirmRisk ? 'Installer quand même' : null;
            if (!installed) {
                btns.push(['install', installLabel || (dl === DL_ERROR ? 'Réessayer l\'installation' : 'Installer'), risky ? 'warn' : 'primary']);
            } else if (s.installedVersionCode < d.versionCode) {
                btns.push(['install', installLabel || 'Mettre à jour', risky ? 'warn' : 'update']);
                if (s.launchable) btns.push(['launch', 'Ouvrir', '']);
                btns.push(['uninstall', 'Désinstaller', 'danger']);
            } else {
                if (s.launchable) btns.push(['launch', 'Ouvrir', 'primary']);
                btns.push(['uninstall', 'Désinstaller', 'danger']);
                if (s.installedVersionCode > d.versionCode) {
                    noteText = `Version plus récente installée (${esc(s.installedVersionName || s.installedVersionCode)}). ` +
                        'Désinstalle-la d\'abord pour installer celle-ci.';
                }
            }
            if (installed && s.installedVersionCode === d.versionCode) {
                noteText = noteText || `Installée (v${esc(s.installedVersionName || d.version)})`;
            }
            if (dl === DL_ERROR) noteText = 'Le téléchargement a échoué.' + (s.downloadError ? ` (${esc(s.downloadError)})` : '') +
                (s.downloadErrorDetail ? ` Reçu : ${esc(s.downloadErrorDetail)}` : '');
        }
        if (risky && confirmRisk && !downloading && !installing) {
            noteText = d.security.verdict === 'malware'
                ? '⛔ L\'analyse a détecté un logiciel malveillant. Appuie encore pour installer quand même.'
                : '⚠ L\'analyse juge cette app suspecte. Appuie encore pour installer quand même.';
        }
        const fav = window.TvFavorites && window.TvFavorites.has(d.id);
        btns.push(['fav', fav ? '★ Favori' : '☆ Favori', fav ? 'fav-on' : '']);
        if (document.documentElement.classList.contains('mobile')) btns.push(['send-tv', '📺 Envoyer à la TV', '']);
        if (d.authorId) btns.push(['author', 'Store de ' + (d.author || 'l\'auteur'), '']);

        box.innerHTML = btns.map(([act, label, cls]) =>
            `<button class="btn focusable ${cls}" data-fk="act-${act}" data-action="${act}">${label}</button>`
        ).join('') + progress;
        note.innerHTML = noteText ? `<div class="d-note">${noteText}</div>` : '';

        box.querySelectorAll('[data-action]').forEach(b => b.addEventListener('click', () => onAction(b.dataset.action)));

        // Rendre le focus au même bouton (ou au premier si l'ancien a disparu)
        if (focusedKey) {
            const again = box.querySelector(`[data-fk="${focusedKey}"]`) || box.querySelector('.btn');
            if (again) again.focus({ preventScroll: true });
        }

        // Marque la version installée dans la liste
        root.querySelectorAll('.version').forEach(v => {
            v.classList.toggle('installed', installed && Number(v.dataset.code) === s.installedVersionCode);
        });
    }

    function focusFirstAction() {
        const b = root.querySelector('#d-actions .btn');
        if (b) b.focus({ preventScroll: true });
    }

    function onAction(act) {
        const d = data;
        if (!d) return;
        const risky = d.security && d.security.status === 'completed' &&
            (d.security.verdict === 'malware' || d.security.verdict === 'suspicious');
        switch (act) {
            case 'install':
                if (risky && !confirmRisk) { confirmRisk = true; renderActions(); return; }
                confirmRisk = false;
                state = Object.assign({}, state || {}, { download: DL_AWAIT });
                renderActions();
                call('install', d.id);
                break;
            case 'cancel': call('cancel', d.id); break;
            case 'launch': call('launch', d.package); break;
            case 'uninstall': call('uninstall', d.package); break;
            case 'send-tv': call('sendToTv', d.id, d.name || ''); break;
            case 'back': back(); break;
            case 'classic-reviews': call('openClassic', 'details', d.id); break;
            case 'author': if (window.TvAuthor) window.TvAuthor.open(d.authorId, d.author); break;
            case 'desc': {
                showDesc = !showDesc;
                render(true);
                const t = root.querySelector('[data-fk="desc-toggle"]');
                if (t) t.focus({ preventScroll: true });
                if (showDesc) { const blk = root.querySelector('[data-fk="desc"]'); if (blk) blk.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }
                break;
            }
            case 'v-sort':
                vprefs.recent = !vprefs.recent; saveVprefs(); renderVersions('v-sort'); break;
            case 'v-compat':
                vprefs.compat = !vprefs.compat; saveVprefs(); renderVersions('v-compat'); break;
            case 'fav':
                if (window.TvFavorites) {
                    window.TvFavorites.toggle({
                        id: d.id, name: d.name, icon: d.icon, downloads: d.downloadsText,
                        size: d.size, package: d.package,
                    });
                }
                renderActions();
                break;
            case 'ai':
                data.aiStatus = 'pending';
                render(false);
                const ai = root.querySelector('[data-fk="ai"]');
                if (ai) ai.focus({ preventScroll: true });
                call('requestAISummary', d.id);
                break;
        }
    }

    function bindEvents() {
        root.querySelectorAll('[data-action="ai"]').forEach(b => b.addEventListener('click', () => onAction('ai')));
        root.querySelectorAll('[data-action="desc"], [data-action="back"], [data-action="classic-reviews"]').forEach(b =>
            b.addEventListener('click', (e) => { e.stopPropagation(); onAction(b.dataset.action); }));
        root.querySelectorAll('.shot').forEach(img => img.addEventListener('click', () => openViewer(Number(img.dataset.shot))));
        root.querySelectorAll('.expandable').forEach(b => b.addEventListener('click', () => expand(b)));
    }

    function expand(block) {
        const t = block.querySelector('.d-text.clamped');
        if (t) { t.classList.remove('clamped'); const m = block.querySelector('.d-more'); if (m) m.remove(); return; }
        const p = block.querySelector('.d-perms');
        if (p && data) {
            const list = data.permissions.map(x => x.replace(/^android\.permission\./, '')).join('\n');
            p.textContent = list || 'aucune';
            p.style.whiteSpace = 'pre-line';
        }
    }

    // ------------------------------------------------------------ visionneuse

    viewer.addEventListener('click', () => { if (!viewer.classList.contains('hidden')) closeViewer(); });

    function openViewer(i) {
        viewerShots = (data && data.screenshots) || [];
        if (!viewerShots.length) return;
        viewerIndex = i;
        showShot();
        viewer.classList.remove('hidden');
        viewer.focus();
    }
    function showShot() {
        viewerImg.src = viewerShots[viewerIndex].original || viewerShots[viewerIndex].preview;
        viewer.querySelector('.viewer-hint').textContent =
            `${viewerIndex + 1} / ${viewerShots.length} · ◀ ▶ pour défiler · Retour pour fermer`;
    }
    function closeViewer() {
        viewer.classList.add('hidden');
        viewerImg.src = '';
        const shot = root.querySelector(`[data-shot="${viewerIndex}"]`);
        if (shot) shot.focus();
    }

    // ------------------------------------------------------------ navigation D-pad

    function focusables() {
        return Array.from(root.querySelectorAll('.focusable, .app-card'))
            .filter(el => el.offsetParent !== null);
    }

    // Navigation spatiale : élément le plus proche dans la direction demandée
    function move(dir) {
        const cur = document.activeElement;
        const list = focusables();
        if (!list.includes(cur)) { focusFirstAction(); return; }
        const r = cur.getBoundingClientRect();
        const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        let best = null, bestScore = Infinity;
        for (const el of list) {
            if (el === cur) continue;
            const e = el.getBoundingClientRect();
            const ex = e.left + e.width / 2, ey = e.top + e.height / 2;
            let main, cross;
            if (dir === 'down') { if (e.top < r.bottom - 4) continue; main = e.top - r.bottom; cross = Math.abs(ex - cx); }
            else if (dir === 'up') { if (e.bottom > r.top + 4) continue; main = r.top - e.bottom; cross = Math.abs(ex - cx); }
            else if (dir === 'right') {
                if (e.left < r.right - 4) continue;
                if (e.bottom < r.top || e.top > r.bottom) continue; // même ligne
                main = e.left - r.right; cross = Math.abs(ey - cy);
            } else {
                if (e.right > r.left + 4) continue;
                if (e.bottom < r.top || e.top > r.bottom) continue;
                main = r.left - e.right; cross = Math.abs(ey - cy);
            }
            const score = main + cross * 2;
            if (score < bestScore) { bestScore = score; best = el; }
        }
        if (best) {
            best.focus({ preventScroll: true });
            best.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
        } else if (dir === 'up') {
            root.scrollTo({ top: 0, behavior: 'smooth' });
        } else if (dir === 'down') {
            // Bas de page : défile le texte long
            root.scrollBy({ top: 200, behavior: 'smooth' });
        }
    }

    function handleKey(e) {
        if (!viewer.classList.contains('hidden')) {
            if (e.key === 'ArrowRight') { viewerIndex = (viewerIndex + 1) % viewerShots.length; showShot(); }
            else if (e.key === 'ArrowLeft') { viewerIndex = (viewerIndex - 1 + viewerShots.length) % viewerShots.length; showShot(); }
            else if (e.key === 'Escape' || e.key === 'Enter') closeViewer();
            e.preventDefault();
            return true;
        }
        if (authorOpen()) return authorKey(e);
        if (!isOpen()) return false;
        const dirs = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
        if (dirs[e.key]) { e.preventDefault(); move(dirs[e.key]); return true; }
        if (e.key === 'Escape') { e.preventDefault(); back(); return true; }
        if (e.key === 'Enter') {
            const el = document.activeElement;
            if (el && el.classList.contains('app-card')) return false; // géré par la carte
            if (el && root.contains(el)) { e.preventDefault(); el.click(); }
            return true;
        }
        // Raccourci : touche jaune / F = favori
        if (e.key === 'ColorF2Yellow' || e.key === 'Yellow' || e.key === 'f' || e.key === 'F') {
            const el = document.activeElement;
            if (!(el && el.classList.contains('app-card'))) onAction('fav');
            return true;
        }
        // Raccourci : touche D / lecture = installer
        if (e.key === 'd' || e.key === 'D' || e.key === 'MediaPlay') {
            const el = document.activeElement;
            if (el && el.classList.contains('app-card')) return true; // la carte s'en charge
            if (root.querySelector('[data-action="install"]')) onAction('install');
            return true;
        }
        return true;
    }

    // ------------------------------------------------------------ appels Android

    window.onDetails = function (json) {
        const d = typeof json === 'string' ? JSON.parse(json) : json;
        if (!isOpen() || String(d.id) !== stack[stack.length - 1]) return;
        const first = !data || data.id !== d.id;
        data = d;
        render(!first);
    };

    window.onDetailsError = function () {
        if (!isOpen()) return;
        root.innerHTML = `<div class="d-loading">Impossible de charger la fiche.<br>Retour pour revenir.</div>`;
    };

    window.onAppState = function (json) {
        const s = typeof json === 'string' ? JSON.parse(json) : json;
        if (!data || String(s.id) !== String(data.id)) return;
        state = s;
        renderActions();
    };

    window.onAIError = function () {
        if (!data) return;
        data.aiStatus = 'idle';
        render(true);
    };

    // ------------------------------------------------------------ store d'un auteur

    const authorEl = document.getElementById('author');
    let author = null;        // {id, name, key, apps}
    let authorReturn = null;

    function authorOpen() { return !authorEl.classList.contains('hidden'); }

    function openAuthor(id, name) {
        if (!id) return;
        authorReturn = document.activeElement;
        author = { id: String(id), name: name || ('profil ' + id), key: 'author:' + id, apps: undefined };
        authorEl.classList.remove('hidden');
        renderAuthor();
        call('loadSource', author.key, 'profile', author.id);
    }

    function closeAuthor(restoreFocus) {
        authorEl.classList.add('hidden');
        authorEl.innerHTML = '';
        author = null;
        if (restoreFocus && authorReturn && document.body.contains(authorReturn)) authorReturn.focus({ preventScroll: true });
    }

    function renderAuthor() {
        if (!author) return;
        const a = document.activeElement;
        const keepId = a && authorEl.contains(a) ? (a.dataset.id || a.dataset.fk) : null;
        const all = author.apps;
        const apps = all ? all.filter(x => !window.TvArch || window.TvArch.isAllowed(x.abi)) : all;
        const hidden = all && apps ? all.length - apps.length : 0;
        authorEl.innerHTML = `
            <div class="author-head">
              <button class="close-btn back-btn mobile-only" type="button" data-fk="author-back" aria-label="Retour">←</button>
              <div style="flex:1;min-width:0">
                <div class="author-title">Store de ${esc(author.name)}</div>
                <div class="author-sub">Profil ${esc(author.id)}${apps ? ' · ' + apps.length + ' app(s)' : ''}${hidden ? ' · ' + hidden + ' masquée(s) (architecture)' : ''}</div>
              </div>
              <div class="d-actions">
                <button class="btn focusable" data-fk="author-home">Afficher sur l'accueil</button>
              </div>
            </div>
            <div class="author-grid">${
                all === undefined ? '<div class="d-loading" style="height:auto;padding:40px">Chargement…</div>'
                : all === null ? '<div class="d-loading" style="height:auto;padding:40px">Erreur réseau</div>'
                : !apps.length ? '<div class="d-loading" style="height:auto;padding:40px">Aucune application</div>' : ''
            }</div>`;
        const grid = authorEl.querySelector('.author-grid');
        (apps || []).forEach(app => grid.appendChild(window.TvCards.create(app)));
        const backBtn = authorEl.querySelector('[data-fk="author-back"]');
        if (backBtn) backBtn.addEventListener('click', () => closeAuthor(true));
        authorEl.querySelector('[data-fk="author-home"]').addEventListener('click', () => {
            if (window.TvStore) window.TvStore.setProfile(author.id);
            const s = document.getElementById('status');
            if (s) s.textContent = `Store de ${author.name} ajouté à l'accueil`;
            const b = authorEl.querySelector('[data-fk="author-home"]');
            if (b) b.textContent = 'Ajouté à l\'accueil ✔';
        });
        let target = keepId && (authorEl.querySelector(`.app-card[data-id="${CSS.escape(keepId)}"]`) ||
            authorEl.querySelector(`[data-fk="${CSS.escape(keepId)}"]`));
        if (!target) target = grid.querySelector('.app-card') || authorEl.querySelector('.btn');
        if (target) target.focus({ preventScroll: true });
    }

    function authorKey(e) {
        const dir = window.TvNav.DIRS[e.key];
        if (dir) { e.preventDefault(); window.TvNav.move(authorEl, dir); return true; }
        if (e.key === 'Escape') { e.preventDefault(); closeAuthor(true); return true; }
        if (e.key === 'Enter') {
            const el = document.activeElement;
            if (el && el.classList.contains('app-card')) return true; // la carte ouvre la fiche
            if (el && authorEl.contains(el)) { e.preventDefault(); el.click(); }
            return true;
        }
        return true;
    }

    window.TvAuthor = {
        open: openAuthor,
        isOpen: authorOpen,
        onApps(key, apps) {
            if (!author || author.key !== key) return;
            const first = author.apps === undefined;
            author.apps = apps;
            renderAuthor();
            // 1re réponse : sélection sur la première app
            const card = first && authorEl.querySelector('.author-grid .app-card');
            if (card) card.focus({ preventScroll: true });
        },
        onError(key) { if (author && author.key === key && author.apps === undefined) { author.apps = null; renderAuthor(); } },
    };

    window.TvDetails = { open, close, isOpen, back, handleKey };
})();
