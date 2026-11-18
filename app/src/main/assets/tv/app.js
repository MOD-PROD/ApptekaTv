// Appteka TV - Interface logic
// Rangées : "tv" (catégorie 37) et "entertainment" (catégorie 27)

const STATUS = document.getElementById('status');
const SEARCH_BOX = document.getElementById('search-box');
const SEARCH_INPUT = document.getElementById('search-input');

// Rangées visibles, de haut en bas (la rangée "search" n'apparaît qu'après une recherche)
function rowKeys() {
    const keys = [];
    if (!document.getElementById('section-search').hidden) keys.push('search');
    keys.push('tv', 'entertainment');
    return keys;
}

function rowEl(key) { return document.getElementById('row-' + key); }

function createAppCard(app) {
    const card = document.createElement('div');
    card.className = 'app-card';
    card.tabIndex = 0;
    card.dataset.id = app.id;

    const fallback = `<div class="app-icon app-icon-fallback" style="display:flex;align-items:center;justify-content:center;font-size:42px;background:#1e293b;">📺</div>`;
    const iconHtml = app.icon
        ? `<img class="app-icon" src="${app.icon}" alt="" loading="lazy" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';">
           ${fallback.replace('display:flex', 'display:none')}`
        : fallback;

    card.innerHTML = `
        ${iconHtml}
        <div class="app-info">
            <div class="app-name">${escapeHtml(app.name || 'App')}</div>
            <div class="app-meta">${app.downloads || ''} · ${app.size || ''}</div>
            <div class="app-actions">
                <button class="btn-download" type="button" tabindex="-1">Installer</button>
            </div>
        </div>
    `;

    const downloadBtn = card.querySelector('.btn-download');
    card.addEventListener('click', (e) => {
        if (e.target === downloadBtn) return;
        onAppSelected(app);
    });
    downloadBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        onDownload(app);
    });
    card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            onAppSelected(app);
        } else if (e.key === 'd' || e.key === 'D' || e.key === 'MediaPlay' || e.key === 'ColorF0Red') {
            e.preventDefault();
            onDownload(app);
        }
    });
    return card;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function onAppSelected(app) {
    // Fiche HTML (remplace l'écran de détails natif)
    window.TvDetails.open(app.id, app);
}

function onDownload(app) {
    STATUS.textContent = `Téléchargement : ${app.name}…`;
    if (window.Android && typeof window.Android.install === 'function') {
        window.Android.install(String(app.id));
    } else {
        STATUS.textContent = 'Bridge téléchargement indisponible';
    }
}

function focusCard(card) {
    if (!card) return;
    card.focus({ preventScroll: true });
    card.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'center' });
}

function renderRow(key, apps) {
    const row = rowEl(key);
    if (!row) return;

    // Mémorise le focus pour ne pas le perdre quand une page s'ajoute
    const focused = document.activeElement;
    const focusedId = focused && row.contains(focused) ? focused.dataset.id : null;
    const scroll = row.scrollLeft;

    row.innerHTML = '';
    if (!apps || apps.length === 0) {
        row.innerHTML = '<div class="loading">Aucune application</div>';
    } else {
        apps.forEach(app => row.appendChild(createAppCard(app)));
    }
    row.scrollLeft = scroll;

    const count = document.getElementById('count-' + key);
    if (count) count.textContent = apps && apps.length ? `(${apps.length})` : '';

    if (focusedId) {
        const again = row.querySelector(`.app-card[data-id="${CSS.escape(focusedId)}"]`);
        if (again) again.focus({ preventScroll: true });
    } else if ((!window.TvDetails || !window.TvDetails.isOpen()) &&
               (!document.activeElement || !document.activeElement.classList.contains('app-card'))) {
        // Premier chargement : focus sur la 1re carte de la 1re rangée remplie
        for (const k of rowKeys()) {
            const first = rowEl(k) && rowEl(k).querySelector('.app-card');
            if (first) { focusCard(first); break; }
        }
    }
    updateStatus();
}

function updateStatus() {
    const total = rowKeys().reduce((n, k) => n + (rowEl(k) ? rowEl(k).querySelectorAll('.app-card').length : 0), 0);
    STATUS.textContent = `${total} applications · ◀ ▶ ▲ ▼ naviguer · OK = détails · D = installer`;
}

function firstCardOfTopRow() {
    for (const k of rowKeys()) {
        const c = rowEl(k) && rowEl(k).querySelector('.app-card');
        if (c) return c;
    }
    return null;
}

function focusSearchBox() {
    SEARCH_BOX.classList.remove('editing');
    SEARCH_INPUT.blur();
    SEARCH_BOX.focus();
    document.querySelector('.content').scrollTop = 0;
}

function startEditing() {
    SEARCH_BOX.classList.add('editing');
    SEARCH_INPUT.focus();
    // place le curseur à la fin
    const v = SEARCH_INPUT.value; SEARCH_INPUT.value = ''; SEARCH_INPUT.value = v;
}

function submitSearch() {
    const q = SEARCH_INPUT.value.trim();
    SEARCH_INPUT.blur();
    SEARCH_BOX.classList.remove('editing');
    if (!q) { closeSearch(); focusSearchBox(); return; }
    const section = document.getElementById('section-search');
    section.hidden = false;
    rowEl('search').innerHTML = '<div class="loading">Recherche…</div>';
    document.getElementById('count-search').textContent = '';
    STATUS.textContent = `Recherche : ${q}…`;
    if (window.Android && typeof window.Android.search === 'function') {
        window.Android.search(q);
    } else {
        rowEl('search').innerHTML = '<div class="loading">Recherche indisponible</div>';
    }
    SEARCH_BOX.focus();
}

function closeSearch() {
    const section = document.getElementById('section-search');
    if (section.hidden) return false;
    section.hidden = true;
    rowEl('search').innerHTML = '';
    SEARCH_INPUT.value = '';
    updateStatus();
    return true;
}

function setupFocusManagement() {
    SEARCH_BOX.addEventListener('submit', (e) => { e.preventDefault(); submitSearch(); });

    document.addEventListener('keydown', (e) => {
        // La fiche app (et sa visionneuse) gère ses propres touches
        if (window.TvDetails && window.TvDetails.handleKey(e)) return;
        if (window.TvDetails && window.TvDetails.isOpen()) return;
        const focused = document.activeElement;

        // 1) Saisie en cours dans le champ : on laisse le clavier faire,
        //    sauf Entrée (valider), ▼ (sortir vers les apps) et Échap.
        if (focused === SEARCH_INPUT) {
            if (e.key === 'Enter') { e.preventDefault(); submitSearch(); }
            else if (e.key === 'Escape') { e.preventDefault(); focusSearchBox(); }
            else if (e.key === 'ArrowDown') {
                e.preventDefault();
                SEARCH_INPUT.blur(); SEARCH_BOX.classList.remove('editing');
                focusCard(firstCardOfTopRow());
            }
            return;
        }

        // 2) Barre de recherche sélectionnée (pas encore en saisie)
        if (focused === SEARCH_BOX) {
            if (e.key === 'Enter') { e.preventDefault(); startEditing(); }
            else if (e.key === 'ArrowDown') { e.preventDefault(); focusCard(firstCardOfTopRow()); }
            else if (e.key.startsWith('Arrow')) { e.preventDefault(); }
            return;
        }

        // 3) Rien de sélectionné : une flèche reprend le focus
        if (!focused || !focused.classList.contains('app-card')) {
            if (e.key.startsWith('Arrow')) {
                e.preventDefault();
                const first = firstCardOfTopRow();
                first ? focusCard(first) : focusSearchBox();
            }
            return;
        }

        // 4) Navigation entre les cartes
        const row = focused.parentElement;
        const cards = Array.from(row.querySelectorAll('.app-card'));
        const idx = cards.indexOf(focused);
        const keys = rowKeys();

        if (e.key === 'ArrowRight') {
            e.preventDefault();
            focusCard(cards[idx + 1]);
        } else if (e.key === 'ArrowLeft') {
            e.preventDefault();
            focusCard(cards[idx - 1]);
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const r = keys.indexOf(row.dataset.key);
            const step = e.key === 'ArrowDown' ? 1 : -1;
            let moved = false;
            for (let i = r + step; i >= 0 && i < keys.length; i += step) {
                const target = Array.from(rowEl(keys[i]).querySelectorAll('.app-card'));
                if (target.length) {
                    focusCard(target[Math.min(idx, target.length - 1)]);
                    moved = true;
                    break;
                }
            }
            if (!moved && step < 0) focusSearchBox();
        }
    });
}

// Touche Retour (appelée par Android). Renvoie true si géré ici.
window.onTvBack = function() {
    if (window.TvDetails && window.TvDetails.back()) return true;
    const focused = document.activeElement;
    if (focused === SEARCH_INPUT) { focusSearchBox(); return true; }
    if (closeSearch()) {
        const first = firstCardOfTopRow();
        first ? focusCard(first) : focusSearchBox();
        return true;
    }
    if (focused === SEARCH_BOX) {
        const first = firstCardOfTopRow();
        if (first) { focusCard(first); return true; }
    }
    return false;
};

window.onSearchResults = function(json) {
    try {
        const apps = typeof json === 'string' ? JSON.parse(json) : json;
        renderRow('search', apps);
        if (!apps.length) {
            rowEl('search').innerHTML = '<div class="loading">Aucun résultat</div>';
        }
        const first = rowEl('search').querySelector('.app-card');
        if (first) focusCard(first);
    } catch (e) {
        rowEl('search').innerHTML = '<div class="loading">Erreur de recherche</div>';
    }
};

// Appelé par Android : loadTvApps(key, json) ; ancien format loadTvApps(json) = rangée TV
window.loadTvApps = function(keyOrJson, maybeJson) {
    try {
        const key = maybeJson === undefined ? 'tv' : keyOrJson;
        const raw = maybeJson === undefined ? keyOrJson : maybeJson;
        const apps = typeof raw === 'string' ? JSON.parse(raw) : raw;
        renderRow(key, apps);
    } catch (e) {
        console.error('Failed to parse apps', e);
        STATUS.textContent = 'Erreur de chargement';
    }
};

window.onCategoryError = function(key) {
    const row = rowEl(key);
    if (row) row.innerHTML = '<div class="loading">Erreur réseau</div>';
};

window.onDownloadStarted = function(json) {
    let name = json;
    try { const o = JSON.parse(json); name = o.name || o.id; } catch (e) {}
    STATUS.textContent = `Téléchargement lancé : ${name} — installation automatique à la fin`;
};

// Nouveau format envoyé par Android : { key, apps }
window.loadTvAppsJson = function(json) {
    try {
        const o = typeof json === 'string' ? JSON.parse(json) : json;
        renderRow(o.key, o.apps);
    } catch (e) {
        console.error('Failed to parse apps', e);
    }
};

// Les cartes sont réutilisées dans la fiche (apps similaires)
window.TvCards = { create: createAppCard };

document.addEventListener('DOMContentLoaded', setupFocusManagement);
