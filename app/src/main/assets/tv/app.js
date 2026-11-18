// Appteka TV — page d'accueil
// Rangées configurables (damier 5×9 : 42 catégories + Toutes, Favoris, Store),
// favoris locaux, recherche, navigation télécommande.

const STATUS = document.getElementById('status');
const ROWS = document.getElementById('rows');
const SEARCH_BOX = document.getElementById('search-box');
const SEARCH_INPUT = document.getElementById('search-input');
const BTN_ADD = document.getElementById('btn-add');
const PICKER = document.getElementById('picker');
const PICKER_GRID = document.getElementById('picker-grid');
const STORE_EDITOR = document.getElementById('store-editor');
const STORE_INPUT = document.getElementById('store-input');
const STORE_SAVE = document.getElementById('store-save');

const GRID_COLS = 9;
const DEFAULT_SOURCES = ['cat:37', 'cat:27']; // TV, Entertainment

// Icônes maison (traits dorés)
const ICONS = {
    all: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></svg>',
    fav: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.25 4.1 1 5.85L12 16.9l-5.25 2.75 1-5.85L3.5 9.7l5.9-.9z"/></svg>',
    store: '<svg viewBox="0 0 24 24"><path d="M4 9l1.5-5h13L20 9"/><path d="M4 9h16v2a3 3 0 0 1-5.33 1.9A3 3 0 0 1 12 14a3 3 0 0 1-2.67-1.1A3 3 0 0 1 4 11z"/><path d="M5.5 13.5V20h13v-6.5"/><path d="M10 20v-4h4v4"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
    cat: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="4"/></svg>',
};

// ------------------------------------------------------------------ état

let categories = [];          // [{id, name, icon}]
let settings = loadJson('settings', null) || { sources: DEFAULT_SOURCES.slice(), profile: '' };
let favorites = loadJson('favorites', []);   // cartes complètes
const rowData = {};           // key -> [apps] | null (erreur) | undefined (en cours)
const rowLoading = {};        // key -> true
let searchQuery = '';

// ------------------------------------------------------------ préférences

function getPref(key) {
    try {
        if (window.Android && typeof window.Android.getPref === 'function') return window.Android.getPref(key);
        return window.localStorage.getItem('tv_' + key);
    } catch (e) { return null; }
}
function setPref(key, value) {
    try {
        if (window.Android && typeof window.Android.setPref === 'function') window.Android.setPref(key, value);
        else window.localStorage.setItem('tv_' + key, value);
    } catch (e) { /* stockage indisponible */ }
}
function loadJson(key, def) {
    try { const v = getPref(key); return v ? JSON.parse(v) : def; } catch (e) { return def; }
}
function saveSettings() { setPref('settings', JSON.stringify(settings)); }
function saveFavorites() { setPref('favorites', JSON.stringify(favorites)); }

function call(fn, ...args) {
    if (window.Android && typeof window.Android[fn] === 'function') window.Android[fn](...args.map(String));
}

// ------------------------------------------------------------ utilitaires

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text == null ? '' : String(text);
    return div.innerHTML;
}

// SVG venant de l'API : on retire scripts et gestionnaires d'événements
function safeSvg(svg) {
    if (!svg || svg.trim().indexOf('<svg') !== 0) return null;
    return svg
        .replace(/<script[\s\S]*?<\/script>/gi, '')
        .replace(/\son\w+\s*=\s*(".*?"|'.*?'|[^\s>]+)/gi, '')
        .replace(/javascript:/gi, '');
}

function categoryById(id) { return categories.find(c => String(c.id) === String(id)); }

function iconFor(source) {
    if (source === 'all') return { html: ICONS.all, cls: 'line', name: 'Toutes les catégories' };
    if (source === 'fav') return { html: ICONS.fav, cls: 'line', name: 'Favoris' };
    if (source === 'store') return { html: ICONS.store, cls: 'line', name: 'Store · profil ' + settings.profile };
    if (source === 'search') return { html: ICONS.search, cls: 'line', name: 'Résultats : ' + searchQuery };
    const c = categoryById(source.slice(4));
    const svg = c && safeSvg(c.icon);
    return svg ? { html: svg, cls: 'api', name: c.name } : { html: ICONS.cat, cls: 'line', name: c ? c.name : 'Catégorie' };
}

// ------------------------------------------------------------ favoris

const TvFavorites = {
    has(id) { return favorites.some(f => String(f.id) === String(id)); },
    toggle(app) {
        if (this.has(app.id)) {
            favorites = favorites.filter(f => String(f.id) !== String(app.id));
            STATUS.textContent = `Retiré des favoris : ${app.name}`;
        } else {
            favorites.unshift({
                id: app.id, name: app.name, icon: app.icon || '',
                downloads: app.downloads || '', size: app.size || '', package: app.package || '',
            });
            STATUS.textContent = settings.sources.includes('fav')
                ? `Ajouté aux favoris : ${app.name}`
                : `Ajouté aux favoris : ${app.name} — active « Favoris » avec + pour afficher la rangée`;
        }
        saveFavorites();
        rowData.fav = favorites.slice();
        refreshRow('fav');
        document.querySelectorAll('.app-card').forEach(c => updateFavBadge(c));
        return this.has(app.id);
    },
};
window.TvFavorites = TvFavorites;

// ------------------------------------------------------------ cartes

function createAppCard(app) {
    const card = document.createElement('div');
    card.className = 'app-card';
    card.tabIndex = 0;
    card.dataset.id = app.id;

    const fallback = '<div class="app-icon app-icon-fallback">📺</div>';
    const icon = app.icon
        ? `<img class="app-icon" src="${escapeHtml(app.icon)}" alt="" loading="lazy">`
        : fallback;

    card.innerHTML = `
        ${icon}
        <div class="app-name">${escapeHtml(app.name || 'App')}</div>
        <div class="app-meta">${escapeHtml(app.downloads || '')}${app.size ? ' · ' + escapeHtml(app.size) : ''}</div>
        <div class="app-hint">OK · fiche</div>`;
    const img = card.querySelector('img.app-icon');
    if (img) img.addEventListener('error', () => { img.outerHTML = fallback; });
    updateFavBadge(card);

    card.addEventListener('click', () => onAppSelected(app));
    card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); onAppSelected(app); }
        else if (isInstallKey(e)) { e.preventDefault(); onDownload(app); }
        else if (isFavKey(e)) { e.preventDefault(); TvFavorites.toggle(app); }
    });
    return card;
}

function updateFavBadge(card) {
    const has = card.dataset.id && TvFavorites.has(card.dataset.id);
    let badge = card.querySelector('.fav-badge');
    if (has && !badge) {
        badge = document.createElement('div');
        badge.className = 'fav-badge';
        badge.textContent = '★';
        card.appendChild(badge);
    } else if (!has && badge) {
        badge.remove();
    }
}

function isInstallKey(e) { return e.key === 'd' || e.key === 'D' || e.key === 'MediaPlay' || e.key === 'MediaPlayPause'; }
function isFavKey(e) { return e.key === 'f' || e.key === 'F' || e.key === 'ColorF2Yellow' || e.key === 'Yellow'; }

function onAppSelected(app) { window.TvDetails.open(app.id, app); }

function onDownload(app) {
    STATUS.textContent = `Téléchargement : ${app.name}…`;
    call('install', app.id);
}

function focusCard(card) {
    if (!card) return;
    card.focus({ preventScroll: true });
    card.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
}

// ------------------------------------------------------------ rangées

function visibleSources() {
    const list = [];
    if (searchQuery) list.push('search');
    settings.sources.forEach(s => {
        if (s === 'store' && !settings.profile) return;
        list.push(s);
    });
    return list;
}

function rowEl(key) { return ROWS.querySelector(`.row[data-key="${CSS.escape(key)}"]`); }

/** Reconstruit la liste des rangées selon les sources cochées. */
function buildRows() {
    const keys = visibleSources();
    const a = document.activeElement;
    const focusedId = a && a.classList.contains('app-card') ? a.dataset.id : null;
    const focusedRow = focusedId && a.closest('.row') ? a.closest('.row').dataset.key : null;

    ROWS.innerHTML = '';
    if (!keys.length) {
        ROWS.innerHTML = '<div class="empty-hint">Aucune catégorie affichée. Sélectionne + en haut à droite pour en choisir.</div>';
    }
    keys.forEach(key => {
        const ic = iconFor(key);
        const row = document.createElement('section');
        row.className = 'row';
        row.dataset.key = key;
        row.innerHTML = `
            <div class="row-icon glass" title="${escapeHtml(ic.name)}">
                <div class="icon-svg ${ic.cls}">${ic.html}</div>
                <span class="row-count"></span>
            </div>
            <div class="apps-row"></div>`;
        ROWS.appendChild(row);
        fillRow(key);
        ensureLoaded(key);
    });

    if (focusedRow && focusedId) {
        const row = rowEl(focusedRow);
        const card = row && row.querySelector(`.app-card[data-id="${CSS.escape(focusedId)}"]`);
        if (card) card.focus({ preventScroll: true });
    }
    updateStatus();
}

function fillRow(key) {
    const row = rowEl(key);
    if (!row) return;
    const box = row.querySelector('.apps-row');
    const apps = rowData[key];
    const scroll = box.scrollLeft;
    const focused = document.activeElement;
    const focusedId = focused && box.contains(focused) ? focused.dataset.id : null;

    box.innerHTML = '';
    if (apps === undefined) {
        box.innerHTML = '<div class="loading">Chargement…</div>';
    } else if (apps === null) {
        box.innerHTML = '<div class="loading">Erreur réseau</div>';
    } else if (!apps.length) {
        box.innerHTML = key === 'fav'
            ? '<div class="empty-hint">Aucun favori. Dans une fiche, choisis « Favori » (ou touche jaune sur une carte).</div>'
            : '<div class="loading">Aucune application</div>';
    } else {
        apps.forEach(app => box.appendChild(createAppCard(app)));
    }
    box.scrollLeft = scroll;
    row.querySelector('.row-count').textContent = apps && apps.length ? apps.length : '';

    if (focusedId) {
        const again = box.querySelector(`.app-card[data-id="${CSS.escape(focusedId)}"]`);
        (again || box.querySelector('.app-card') || BTN_ADD).focus({ preventScroll: true });
    } else if (!overlayOpen() && !isMainFocused()) {
        const first = firstCard();
        if (first) focusCard(first);
    }
}

function refreshRow(key) { if (rowEl(key)) fillRow(key); }

function ensureLoaded(key) {
    if (key === 'fav') { rowData.fav = favorites.slice(); fillRow('fav'); return; }
    if (key === 'search') return;
    if (rowData[key] !== undefined || rowLoading[key]) return;
    rowLoading[key] = true;
    if (key === 'all') call('loadSource', key, 'all', '');
    else if (key === 'store') call('loadSource', key, 'profile', settings.profile);
    else if (key.indexOf('cat:') === 0) call('loadSource', key, 'category', key.slice(4));
}

function firstCard() {
    for (const row of ROWS.querySelectorAll('.row')) {
        const c = row.querySelector('.app-card');
        if (c) return c;
    }
    return null;
}

function isMainFocused() {
    const a = document.activeElement;
    return a && (a.classList.contains('app-card') || a === SEARCH_BOX || a === SEARCH_INPUT || a === BTN_ADD);
}

function overlayOpen() {
    return !PICKER.classList.contains('hidden') || !STORE_EDITOR.classList.contains('hidden') ||
        (window.TvDetails && window.TvDetails.isOpen());
}

function updateStatus() {
    const total = ROWS.querySelectorAll('.app-card').length;
    STATUS.textContent = `${total} applications · OK : fiche · ▶ : installer · jaune : favori · + : catégories`;
}

// ------------------------------------------------------------ recherche

function focusSearchBox() {
    SEARCH_BOX.classList.remove('editing');
    SEARCH_INPUT.blur();
    SEARCH_BOX.focus();
    ROWS.scrollTop = 0;
}

function startEditing() {
    SEARCH_BOX.classList.add('editing');
    SEARCH_INPUT.focus();
    const v = SEARCH_INPUT.value; SEARCH_INPUT.value = ''; SEARCH_INPUT.value = v;
}

function submitSearch() {
    const q = SEARCH_INPUT.value.trim();
    SEARCH_INPUT.blur();
    SEARCH_BOX.classList.remove('editing');
    if (!q) { closeSearch(); focusSearchBox(); return; }
    searchQuery = q;
    delete rowData.search;
    SEARCH_BOX.focus();
    buildRows();
    STATUS.textContent = `Recherche : ${q}…`;
    call('search', q);
}

function closeSearch() {
    if (!searchQuery) return false;
    searchQuery = '';
    SEARCH_INPUT.value = '';
    delete rowData.search;
    buildRows();
    return true;
}

// ------------------------------------------------------------ damier des catégories

let pickerItems = [];

function pickerSourceList() {
    const cats = categories.slice().sort((a, b) => a.id - b.id)
        .map(c => ({ source: 'cat:' + c.id, name: c.name, icon: iconFor('cat:' + c.id) }));
    return cats.concat([
        { source: 'all', name: 'Toutes les catégories', icon: iconFor('all'), special: true },
        { source: 'fav', name: 'Favoris', icon: iconFor('fav'), special: true, sub: favorites.length ? favorites.length + ' app(s)' : '' },
        { source: 'store', name: 'Store', icon: iconFor('store'), special: true, sub: settings.profile ? 'profil ' + settings.profile : 'OK : saisir un profil' },
    ]);
}

function renderPicker(focusIndex) {
    pickerItems = pickerSourceList();
    PICKER_GRID.innerHTML = '';
    if (!categories.length) {
        const wait = document.createElement('div');
        wait.className = 'loading';
        wait.style.gridColumn = '1 / -1';
        wait.textContent = 'Chargement des catégories…';
        PICKER_GRID.appendChild(wait);
    }
    pickerItems.forEach((item, i) => {
        const cell = document.createElement('div');
        const checked = settings.sources.includes(item.source) && (item.source !== 'store' || settings.profile);
        cell.className = 'cell glass' + (checked ? ' checked' : '') + (item.special ? ' special' : '');
        cell.tabIndex = 0;
        cell.dataset.index = i;
        cell.title = item.name;
        cell.innerHTML = `
            <span class="cell-check">✓</span>
            <div class="icon-svg ${item.icon.cls}">${item.icon.html}</div>
            <div class="cell-name">${escapeHtml(item.name)}</div>
            ${item.sub ? `<div class="cell-sub">${escapeHtml(item.sub)}</div>` : ''}`;
        cell.addEventListener('click', () => toggleCell(i));
        PICKER_GRID.appendChild(cell);
    });
    const target = PICKER_GRID.querySelector(`.cell[data-index="${focusIndex || 0}"]`) || PICKER_GRID.querySelector('.cell');
    if (target) target.focus({ preventScroll: true });
}

function openPicker() {
    PICKER.classList.remove('hidden');
    renderPicker(0);
    if (!categories.length) call('loadCategories');
}

function closePicker() {
    PICKER.classList.add('hidden');
    buildRows();
    BTN_ADD.focus();
}

function toggleCell(i) {
    const item = pickerItems[i];
    if (!item) return;
    if (item.source === 'store') { openStoreEditor(); return; }
    const idx = settings.sources.indexOf(item.source);
    if (idx >= 0) settings.sources.splice(idx, 1);
    else settings.sources.push(item.source);
    saveSettings();
    renderPicker(i);
}

function pickerMove(key) {
    const cur = document.activeElement;
    const i = cur && cur.classList.contains('cell') ? Number(cur.dataset.index) : 0;
    const n = pickerItems.length;
    let j = i;
    if (key === 'ArrowRight') j = Math.min(n - 1, i + 1);
    else if (key === 'ArrowLeft') j = Math.max(0, i - 1);
    else if (key === 'ArrowDown') j = i + GRID_COLS < n ? i + GRID_COLS : i;
    else if (key === 'ArrowUp') j = i - GRID_COLS >= 0 ? i - GRID_COLS : i;
    const cell = PICKER_GRID.querySelector(`.cell[data-index="${j}"]`);
    if (cell) cell.focus();
}

// ------------------------------------------------------------ profil Store

function openStoreEditor() {
    STORE_EDITOR.classList.remove('hidden');
    STORE_INPUT.value = settings.profile || '';
    STORE_INPUT.focus();
}

function closeStoreEditor() {
    STORE_EDITOR.classList.add('hidden');
    STORE_INPUT.blur();
    const idx = pickerItems.findIndex(it => it.source === 'store');
    renderPicker(idx);
}

function saveStore() {
    // Accepte « 325988 » ou un lien appteka.store/profile/325988
    const raw = STORE_INPUT.value.trim();
    const m = raw.match(/profile\/(\d+)/) || raw.match(/^(\d{1,10})$/);
    if (raw && !m) { STATUS.textContent = 'Numéro de profil invalide'; STORE_INPUT.focus(); return; }
    const profile = m ? m[1] : '';
    const changed = profile !== settings.profile;
    settings.profile = profile;
    const idx = settings.sources.indexOf('store');
    if (profile && idx < 0) settings.sources.push('store');
    if (!profile && idx >= 0) settings.sources.splice(idx, 1);
    if (changed) { delete rowData.store; rowLoading.store = false; }
    saveSettings();
    STATUS.textContent = profile ? `Profil Store enregistré : ${profile}` : 'Profil Store retiré';
    closeStoreEditor();
}

document.getElementById('store-form').addEventListener('submit', (e) => { e.preventDefault(); saveStore(); });

// ------------------------------------------------------------ navigation

function setupFocusManagement() {
    SEARCH_BOX.addEventListener('submit', (e) => { e.preventDefault(); submitSearch(); });
    BTN_ADD.addEventListener('click', openPicker);

    document.addEventListener('keydown', (e) => {
        // Fiche app (et visionneuse) en premier
        if (window.TvDetails && window.TvDetails.handleKey(e)) return;
        if (window.TvDetails && window.TvDetails.isOpen()) return;

        // Fenêtre profil Store : champ ↔ bouton
        if (!STORE_EDITOR.classList.contains('hidden')) {
            const a = document.activeElement;
            if (e.key === 'Escape') { e.preventDefault(); closeStoreEditor(); }
            else if ((e.key === 'ArrowRight' || e.key === 'ArrowDown') && a === STORE_INPUT) { e.preventDefault(); STORE_SAVE.focus(); }
            else if ((e.key === 'ArrowLeft' || e.key === 'ArrowUp') && a === STORE_SAVE) { e.preventDefault(); STORE_INPUT.focus(); }
            else if (e.key === 'Enter' && a === STORE_SAVE) { e.preventDefault(); saveStore(); }
            else if (a !== STORE_INPUT && a !== STORE_SAVE) { STORE_INPUT.focus(); }
            return;
        }

        // Damier
        if (!PICKER.classList.contains('hidden')) {
            if (e.key.startsWith('Arrow')) { e.preventDefault(); pickerMove(e.key); }
            else if (e.key === 'Enter') {
                e.preventDefault();
                const a = document.activeElement;
                if (a && a.classList.contains('cell')) toggleCell(Number(a.dataset.index));
            } else if (e.key === 'Escape') { e.preventDefault(); closePicker(); }
            return;
        }

        const focused = document.activeElement;

        // Saisie de recherche
        if (focused === SEARCH_INPUT) {
            if (e.key === 'Enter') { e.preventDefault(); submitSearch(); }
            else if (e.key === 'Escape') { e.preventDefault(); focusSearchBox(); }
            else if (e.key === 'ArrowDown') {
                e.preventDefault();
                SEARCH_INPUT.blur(); SEARCH_BOX.classList.remove('editing');
                focusCard(firstCard());
            }
            return;
        }

        // En-tête : recherche ↔ +
        if (focused === SEARCH_BOX || focused === BTN_ADD) {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (focused === SEARCH_BOX) startEditing(); else openPicker();
            } else if (e.key === 'ArrowRight' && focused === SEARCH_BOX) { e.preventDefault(); BTN_ADD.focus(); }
            else if (e.key === 'ArrowLeft' && focused === BTN_ADD) { e.preventDefault(); SEARCH_BOX.focus(); }
            else if (e.key === 'ArrowDown') { e.preventDefault(); const c = firstCard(); if (c) focusCard(c); }
            else if (e.key.startsWith('Arrow')) e.preventDefault();
            return;
        }

        // Rien de sélectionné : une flèche reprend le focus
        if (!focused || !focused.classList.contains('app-card')) {
            if (e.key.startsWith('Arrow')) {
                e.preventDefault();
                const c = firstCard();
                c ? focusCard(c) : BTN_ADD.focus();
            }
            return;
        }

        // Cartes
        const box = focused.parentElement;
        const cards = Array.from(box.querySelectorAll('.app-card'));
        const idx = cards.indexOf(focused);

        if (e.key === 'ArrowRight') { e.preventDefault(); focusCard(cards[idx + 1]); }
        else if (e.key === 'ArrowLeft') { e.preventDefault(); focusCard(cards[idx - 1]); }
        else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const rows = Array.from(ROWS.querySelectorAll('.row'));
            const r = rows.indexOf(focused.closest('.row'));
            const step = e.key === 'ArrowDown' ? 1 : -1;
            for (let i = r + step; i >= 0 && i < rows.length; i += step) {
                const target = Array.from(rows[i].querySelectorAll('.app-card'));
                if (target.length) {
                    // garde la même position horizontale à l'écran
                    const x = focused.getBoundingClientRect().left;
                    let best = target[0], bestD = Infinity;
                    target.forEach(t => { const d = Math.abs(t.getBoundingClientRect().left - x); if (d < bestD) { bestD = d; best = t; } });
                    focusCard(best);
                    best.closest('.row').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    return;
                }
            }
            if (step < 0) focusSearchBox();
        }
    });
}

// Touche Retour (appelée par Android). Renvoie true si géré ici.
window.onTvBack = function () {
    if (window.TvDetails && window.TvDetails.back()) return true;
    if (!STORE_EDITOR.classList.contains('hidden')) { closeStoreEditor(); return true; }
    if (!PICKER.classList.contains('hidden')) { closePicker(); return true; }
    const focused = document.activeElement;
    if (focused === SEARCH_INPUT) { focusSearchBox(); return true; }
    if (closeSearch()) {
        const c = firstCard();
        c ? focusCard(c) : focusSearchBox();
        return true;
    }
    if (focused === SEARCH_BOX || focused === BTN_ADD) {
        const c = firstCard();
        if (c) { focusCard(c); return true; }
    }
    return false;
};

// ------------------------------------------------------------ appels Android

window.onCategories = function (json) {
    try {
        const list = typeof json === 'string' ? JSON.parse(json) : json;
        categories = (list || []).map(c => ({ id: Number(c.id), name: c.name, icon: c.icon }));
    } catch (e) { categories = []; }
    // met à jour les icônes des rangées et le damier s'il est ouvert
    ROWS.querySelectorAll('.row').forEach(row => {
        const ic = iconFor(row.dataset.key);
        const holder = row.querySelector('.row-icon');
        holder.title = ic.name;
        const svg = holder.querySelector('.icon-svg');
        svg.className = 'icon-svg ' + ic.cls;
        svg.innerHTML = ic.html;
    });
    if (!PICKER.classList.contains('hidden') && STORE_EDITOR.classList.contains('hidden')) {
        const a = document.activeElement;
        renderPicker(a && a.dataset.index ? Number(a.dataset.index) : 0);
    }
};

window.loadTvAppsJson = function (json) {
    try {
        const o = typeof json === 'string' ? JSON.parse(json) : json;
        rowData[o.key] = o.apps || [];
        rowLoading[o.key] = false;
        fillRow(o.key);
        updateStatus();
    } catch (e) {
        console.error('Failed to parse apps', e);
    }
};

window.onCategoryError = function (key) {
    rowLoading[key] = false;
    if (rowData[key] === undefined) rowData[key] = null;
    fillRow(key);
};

window.onSearchResults = function (json) {
    try {
        const apps = typeof json === 'string' ? JSON.parse(json) : json;
        rowData.search = apps || [];
    } catch (e) {
        rowData.search = null;
    }
    fillRow('search');
    const row = rowEl('search');
    const first = row && row.querySelector('.app-card');
    if (first && !overlayOpen()) focusCard(first);
    updateStatus();
};

window.onDownloadStarted = function (json) {
    let name = json;
    try { const o = JSON.parse(json); name = o.name || o.id; } catch (e) { /* texte brut */ }
    STATUS.textContent = `Téléchargement lancé : ${name} — installation automatique à la fin`;
};

// Les cartes sont réutilisées dans la fiche (apps similaires)
window.TvCards = { create: createAppCard };

function start() {
    setupFocusManagement();
    buildRows();
    call('loadCategories');
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
else start();
