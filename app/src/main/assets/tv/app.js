// Appteka TV — page d'accueil
// Rangées configurables et réordonnables, damier 5×9 des catégories, recherche
// filtrée par catégories, réglages (taille des cartes, architectures, compte),
// favoris locaux, navigation télécommande.

const STATUS = document.getElementById('status');
const ROWS = document.getElementById('rows');
const SEARCH_BOX = document.getElementById('search-box');
const SEARCH_INPUT = document.getElementById('search-input');
const BTN_SCOPE = document.getElementById('btn-scope');
const BTN_ADD = document.getElementById('btn-add');
const BTN_SETTINGS = document.getElementById('btn-settings');
const BTN_EXIT = document.getElementById('btn-exit');
const BTN_PAIR = document.getElementById('btn-pair');
// La gélule du logo : bouton « fun » qui relance le splash gravité
const BTN_FUN = document.getElementById('btn-fun');
// Le bouton d'association n'existe que sur la TV (le portable n'affiche pas de QR)
const HEADER_ITEMS = [BTN_FUN].concat(document.documentElement.classList.contains('mobile') ? [] : [BTN_PAIR])
    .concat([BTN_SCOPE, SEARCH_BOX, BTN_ADD, BTN_SETTINGS, BTN_EXIT]);
const PICKER = document.getElementById('picker');
const PICKER_GRID = document.getElementById('picker-grid');
const PICKER_TITLE = document.getElementById('picker-title');
const PICKER_HINT = document.getElementById('picker-hint');
const STORE_EDITOR = document.getElementById('store-editor');
const STORE_INPUT = document.getElementById('store-input');
const STORE_SAVE = document.getElementById('store-save');
const SETTINGS = document.getElementById('settings');
const SETTINGS_PANEL = document.getElementById('settings-panel');

const GRID_COLS = 9;
const IS_MOBILE = document.documentElement.classList.contains('mobile');
// Tactile : pas d'anneau de sélection « télécommande » qui reste après un appui
if (IS_MOBILE) {
    document.addEventListener('click', () => setTimeout(() => {
        const a = document.activeElement;
        if (a && a !== document.body && a.tagName !== 'INPUT' && a.tagName !== 'TEXTAREA') a.blur();
    }, 0), true);
}
const PAIR_MODAL = document.getElementById('pair-modal');
const DEFAULT_SOURCES = ['cat:37', 'cat:27']; // TV, Entertainment
const LONG_PRESS_MS = 600;
const ALL_ABIS = [
    ['arm64-v8a', 'ARM 64 bits'],
    ['armeabi-v7a', 'ARM 32 bits'],
    ['armeabi', 'ARM (ancien)'],
    ['x86_64', 'x86 64 bits'],
    ['x86', 'x86'],
    ['universal', 'Universelle'],
];

// Icônes maison (traits dorés)
const ICONS = {
    all: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/></svg>',
    fav: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.25 4.1 1 5.85L12 16.9l-5.25 2.75 1-5.85L3.5 9.7l5.9-.9z"/></svg>',
    store: '<svg viewBox="0 0 24 24"><path d="M4 9l1.5-5h13L20 9"/><path d="M4 9h16v2a3 3 0 0 1-5.33 1.9A3 3 0 0 1 12 14a3 3 0 0 1-2.67-1.1A3 3 0 0 1 4 11z"/><path d="M5.5 13.5V20h13v-6.5"/><path d="M10 20v-4h4v4"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
    cat: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="4"/></svg>',
};

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

function call(fn, ...args) {
    if (window.Android && typeof window.Android[fn] === 'function') return window.Android[fn](...args.map(String));
    return undefined;
}

// ------------------------------------------------------------------ état

let categories = [];          // [{id, name, icon}]
const settings = Object.assign(
    { sources: DEFAULT_SOURCES.slice(), profile: '', searchCats: DEFAULT_SOURCES.slice(), cardScale: 1, skin: 'classic', accent: 'gold', archs: null, margin: 20 },
    loadJson('settings', {}) || {}
);
// Première installation : thème sombre ; ne pas écraser les choix existants.
if (settings.skinV !== 3) { if (settings.skinV == null) { settings.skin = 'classic'; settings.accent = 'gold'; } settings.skinV = 3; saveSettings(); }
let favorites = loadJson('favorites', []);
const rowData = {};           // key -> [apps] | null (erreur) | undefined (en cours)
const rowLoading = {};
// ATV (Gonzo 25/11) : cause du dernier échec d'une rangée et nombre de nouveaux essais.
const rowError = {};
const rowRetries = {};
const ROW_MAX_RETRIES = 3;
let searchQuery = '';
let searchRaw = undefined;    // résultats bruts (avant filtre de catégories)
let movingKey = null;         // rangée en cours de déplacement
let pressTimer = null;        // appui long sur une icône de rangée
let pickerMode = 'rows';      // 'rows' ou 'search'

function saveSettings() { setPref('settings', JSON.stringify(settings)); }
function saveFavorites() { setPref('favorites', JSON.stringify(favorites)); }

// ------------------------------------------------------------ architectures

const deviceAbis = (function () {
    try {
        if (window.Android && typeof window.Android.getDeviceAbis === 'function') {
            return JSON.parse(window.Android.getDeviceAbis());
        }
    } catch (e) { /* valeur par défaut */ }
    return ['arm64-v8a', 'armeabi-v7a', 'armeabi'];
})();

const TvArch = {
    deviceAbis,
    allowed() { return settings.archs || deviceAbis.concat(['universal']); },
    // Une app sans architecture déclarée (anciennes fiches) reste visible
    isAllowed(abis) {
        if (!abis || !abis.length) return true;
        const allowed = this.allowed();
        return abis.some(a => allowed.includes(a));
    },
    label(abi) { const f = ALL_ABIS.find(x => x[0] === abi); return f ? f[1] : abi; },
};
window.TvArch = TvArch;

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
    if (source === 'flag:os') return { html: ICONS.cat, cls: 'line', name: 'Open source' };
    if (source === 'flag:excl') return { html: ICONS.fav, cls: 'line', name: 'Exclusive' };
    if (source === 'search') return { html: ICONS.search, cls: 'line', name: 'Résultats : ' + searchQuery };
    const c = categoryById(source.slice(4));
    const svg = c && safeSvg(c.icon);
    return svg ? { html: svg, cls: 'api', name: c.name } : { html: ICONS.cat, cls: 'line', name: c ? c.name : 'Catégorie' };
}

function applyCardScale() {
    const root = document.documentElement.style;
    root.setProperty('--cs', String(settings.cardScale || 1));
    // Marges d'écran : 0 pour une TV récente, jusqu'à 60 si la TV rogne les bords
    const m = settings.margin == null ? 20 : settings.margin;
    root.setProperty('--sx', m + 'px');
    root.setProperty('--sy', Math.max(8, Math.round(m * 0.7)) + 'px');
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
                id: app.id, name: app.name, icon: app.icon || '', downloads: app.downloads || '',
                size: app.size || '', package: app.package || '', abi: app.abi || [], category: app.category,
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
    card._app = app;

    const fallback = '<div class="app-icon app-icon-fallback">📺</div>';
    const icon = app.icon ? `<img class="app-icon" src="${escapeHtml(app.icon)}" alt="" loading="lazy">` : fallback;
    card.innerHTML = `
        ${icon}
        <div class="app-name">${escapeHtml(app.name || 'App')}</div>
        <div class="app-meta">${escapeHtml(app.downloads || '')}${app.size ? ' · ' + escapeHtml(app.size) : ''}</div>`;
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
    const focusedRow = a && a.closest && a.closest('.row') ? a.closest('.row').dataset.key : null;
    const iconFocused = a && a.classList.contains('row-icon');

    ROWS.innerHTML = '';
    if (!keys.length) {
        ROWS.innerHTML = '<div class="empty-hint">Aucune catégorie affichée. Sélectionne + en haut à droite pour en choisir.</div>';
    }
    keys.forEach(key => {
        const ic = iconFor(key);
        const row = document.createElement('section');
        row.className = 'row' + (key === movingKey ? ' moving' : '');
        row.dataset.key = key;
        row.innerHTML = `
            <div class="row-icon glass" tabindex="0" title="${escapeHtml(ic.name)}">
                <span class="row-arrow up">▲</span>
                <div class="icon-svg ${ic.cls}">${ic.html}</div>
                <span class="row-count"></span>
                <span class="row-arrow down">▼</span>
            </div>
            <span class="row-label">${escapeHtml(ic.name)}</span>
            <div class="apps-row"></div>`;
        ROWS.appendChild(row);
        fillRow(key);
        ensureLoaded(key);
    });

    if (focusedRow) {
        const row = rowEl(focusedRow);
        if (row && iconFocused) row.querySelector('.row-icon').focus({ preventScroll: true });
        else if (row && focusedId) {
            const card = row.querySelector(`.app-card[data-id="${CSS.escape(focusedId)}"]`);
            if (card) card.focus({ preventScroll: true });
        }
    }
    updateScopeBadge();
    updateStatus();
}

function filteredApps(key) {
    const apps = rowData[key];
    if (!apps || key === 'fav') return apps;
    return apps.filter(a => TvArch.isAllowed(a.abi));
}

function fillRow(key) {
    const row = rowEl(key);
    if (!row) return;
    const box = row.querySelector('.apps-row');
    const raw = rowData[key];
    const apps = filteredApps(key);
    const scroll = box.scrollLeft;
    const focused = document.activeElement;
    const focusedId = focused && box.contains(focused) ? focused.dataset.id : null;

    box.innerHTML = '';
    if (apps === undefined) {
        box.innerHTML = '<div class="loading">Chargement…</div>';
    } else if (apps === null) {
        box.innerHTML = '<div class="loading">Erreur réseau</div>' +
            (rowError[key] ? `<div class="set-sub" data-no-i18n>(${escapeHtml(rowError[key])}${rowRetries[key] < ROW_MAX_RETRIES ? ' · nouvel essai…' : ''})</div>` : '');
    } else if (!apps.length) {
        let msg = 'Aucune application';
        if (key === 'fav') msg = 'Aucun favori. Dans une fiche, choisis « Favori » (ou touche jaune sur une carte).';
        else if (key === 'search' && searchRaw && searchRaw.length) msg = `Aucun résultat dans les catégories choisies (${searchRaw.length} ailleurs : bouton filtre à gauche de la recherche).`;
        else if (raw && raw.length) msg = `Aucune app compatible avec les architectures choisies (${raw.length} masquées).`;
        box.innerHTML = `<div class="empty-hint">${escapeHtml(msg)}</div>`;
    } else {
        apps.forEach(app => box.appendChild(createAppCard(app)));
    }
    box.scrollLeft = scroll;
    const hidden = raw && apps ? raw.length - apps.length : 0;
    row.querySelector('.row-count').textContent = apps && apps.length ? apps.length : '';
    const lbl = row.querySelector('.row-label'); if (lbl) lbl.textContent = iconFor(key).name;
    row.querySelector('.row-icon').title = iconFor(key).name + (hidden > 0 ? ` · ${hidden} masquées (architecture)` : '');

    if (focusedId) {
        const again = box.querySelector(`.app-card[data-id="${CSS.escape(focusedId)}"]`);
        (again || box.querySelector('.app-card') || row.querySelector('.row-icon')).focus({ preventScroll: true });
    } else if (!IS_MOBILE && !overlayOpen() && !isMainFocused()) {
        // TV : la télécommande a besoin d'une sélection de départ (inutile au toucher)
        const first = firstCard();
        if (first) focusCard(first);
    }
}

function refreshRow(key) { if (rowEl(key)) fillRow(key); }
function refreshAllRows() { ROWS.querySelectorAll('.row').forEach(r => fillRow(r.dataset.key)); updateStatus(); }

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
    return a && (a.classList.contains('app-card') || a.classList.contains('row-icon') ||
        HEADER_ITEMS.includes(a) || a === SEARCH_INPUT);
}

function overlayOpen() {
    return !PICKER.classList.contains('hidden') || !STORE_EDITOR.classList.contains('hidden') ||
        !SETTINGS.classList.contains('hidden') ||
        (window.TvAuthor && window.TvAuthor.isOpen()) ||
        (window.TvDetails && window.TvDetails.isOpen());
}

// Plus de barre d'aide permanente : la ligne du bas n'apparaît que pour un message,
// quelques secondes (bulle), ou pendant le déplacement d'une rangée.
function updateStatus() {
    STATUS.classList.toggle('sticky', !!movingKey);
    if (movingKey) STATUS.textContent = '▲ ▼ : déplacer la rangée · OK ou Retour : terminer';
    else if (STATUS.classList.contains('sticky-was')) STATUS.textContent = '';
    STATUS.classList.toggle('sticky-was', !!movingKey);
}

let statusTimer = null;
new MutationObserver(() => {
    const has = STATUS.textContent.trim().length > 0;
    STATUS.classList.toggle('show', has);
    clearTimeout(statusTimer);
    if (has && !movingKey) statusTimer = setTimeout(() => STATUS.classList.remove('show'), 3200);
}).observe(STATUS, { childList: true, characterData: true, subtree: true });

// ------------------------------------------------------------ déplacement des rangées

function startMove(key) {
    if (key === 'search') { STATUS.textContent = 'La rangée de recherche reste en haut'; return; }
    movingKey = key;
    const row = rowEl(key);
    if (row) row.classList.add('moving');
    updateStatus();
}

function endMove() {
    if (!movingKey) return false;
    const row = rowEl(movingKey);
    if (row) row.classList.remove('moving');
    movingKey = null;
    updateStatus();
    return true;
}

function moveRow(step) {
    const i = settings.sources.indexOf(movingKey);
    if (i < 0) return;
    // saute les sources masquées (Store sans profil)
    let j = i + step;
    while (j >= 0 && j < settings.sources.length && settings.sources[j] === 'store' && !settings.profile) j += step;
    if (j < 0 || j >= settings.sources.length) return;
    const tmp = settings.sources[i];
    settings.sources[i] = settings.sources[j];
    settings.sources[j] = tmp;
    saveSettings();
    buildRows();
    const row = rowEl(movingKey);
    if (row) {
        row.querySelector('.row-icon').focus({ preventScroll: true });
        row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
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

// Gonzo 25/11 : « Open source » et « Exclusive » (comme l'appli d'origine) sont des filtres
// rangés avec les catégories de recherche (clés flag:*) ; ils s'appliquent aux catégories cochées.
const SEARCH_FLAGS = [
    { source: 'flag:os', name: 'Open source', test: a => !!a.openSource },
    { source: 'flag:excl', name: 'Exclusive', test: a => !!a.exclusive },
];
function searchCatKeys() { return settings.searchCats.filter(s => s.indexOf('flag:') !== 0); }
function searchFlagsOn() { return SEARCH_FLAGS.filter(f => settings.searchCats.includes(f.source)); }

function searchScopeAll() { const cats = searchCatKeys(); return !cats.length || cats.includes('all'); }

function applySearchFilter() {
    if (searchRaw === undefined || searchRaw === null) { rowData.search = searchRaw; return; }
    const flags = searchFlagsOn();
    rowData.search = (searchScopeAll()
        ? searchRaw.slice()
        : searchRaw.filter(a => a.category != null && settings.searchCats.includes('cat:' + a.category)))
        .filter(a => flags.every(f => f.test(a)));
}

function submitSearch() {
    const q = SEARCH_INPUT.value.trim();
    SEARCH_INPUT.blur();
    SEARCH_BOX.classList.remove('editing');
    if (!q) { closeSearch(); focusSearchBox(); return; }
    searchQuery = q;
    searchRaw = undefined;
    delete rowData.search;
    SEARCH_BOX.focus();
    buildRows();
    STATUS.textContent = `Recherche : ${q}…`;
    call('search', q);
}

function closeSearch() {
    if (!searchQuery) return false;
    searchQuery = '';
    searchRaw = undefined;
    SEARCH_INPUT.value = '';
    delete rowData.search;
    buildRows();
    return true;
}

function updateScopeBadge() {
    const badge = document.getElementById('scope-count');
    const flags = searchFlagsOn().map(f => f.name);
    badge.textContent = (searchScopeAll() ? '∞' : String(searchCatKeys().length)) + (flags.length ? '+' : '');
    BTN_SCOPE.title = (searchScopeAll() ? 'Recherche dans toutes les catégories'
        : 'Recherche dans : ' + searchCatKeys().map(s => (categoryById(s.slice(4)) || {}).name || s).join(', '))
        + (flags.length ? ' · ' + flags.join(' + ') : '');
}

// ------------------------------------------------------------ damier des catégories

let pickerItems = [];
let pickerCells = [];

// Gonzo 25/11 : catégories utiles sur une TV en premier (TV, divertissement, lecteurs vidéo, famille…).
const CATEGORY_FIRST = [/\btv\b|télé/i, /entertain|divertis/i, /vid[eé]o/i, /famil/i];
function categoryRank(c) {
    const i = CATEGORY_FIRST.findIndex(re => re.test(c.name || ''));
    return i < 0 ? CATEGORY_FIRST.length : i;
}

function pickerSourceList() {
    const cats = categories.slice().sort((a, b) => categoryRank(a) - categoryRank(b) || a.id - b.id)
        .map(c => ({ source: 'cat:' + c.id, name: c.name, icon: iconFor('cat:' + c.id) }));
    if (pickerMode === 'search') {
        // En haut à gauche : les deux filtres (autre couleur), puis « Toutes les catégories ».
        return SEARCH_FLAGS.map(f => ({ source: f.source, name: f.name, icon: iconFor(f.source), special: true, flag: true }))
            .concat([{ source: 'all', name: 'Toutes les catégories', icon: iconFor('all'), special: true }])
            .concat(cats);
    }
    return cats.concat([
        { source: 'all', name: 'Toutes les catégories', icon: iconFor('all'), special: true },
        { source: 'fav', name: 'Favoris', icon: iconFor('fav'), special: true, sub: favorites.length ? favorites.length + ' app(s)' : '' },
        { source: 'store', name: 'Store', icon: iconFor('store'), special: true, sub: settings.profile ? 'profil ' + settings.profile : 'OK : saisir un profil' },
    ]);
}

function isChecked(item) {
    if (pickerMode === 'search') return settings.searchCats.includes(item.source);
    return settings.sources.includes(item.source) && (item.source !== 'store' || settings.profile);
}

function renderPicker(focusIndex) {
    pickerItems = pickerSourceList();
    const fragment = document.createDocumentFragment();
    if (!categories.length) {
        const wait = document.createElement('div');
        wait.className = 'loading';
        wait.style.gridColumn = '1 / -1';
        wait.textContent = 'Chargement des catégories…';
        fragment.appendChild(wait);
    }
    pickerCells = pickerItems.map((item, i) => {
        const cell = document.createElement('div');
        cell.className = 'cell glass' + (isChecked(item) ? ' checked' : '') + (item.special ? ' special' : '')
            + (item.flag ? ' flag' : '');
        cell.tabIndex = 0;
        cell.dataset.index = i;
        cell.title = item.name;
        cell.innerHTML = `
            <span class="cell-check">✓</span>
            <div class="icon-svg ${item.icon.cls}">${item.icon.html}</div>
            <div class="cell-name">${escapeHtml(item.name)}</div>
            ${item.sub ? `<div class="cell-sub">${escapeHtml(item.sub)}</div>` : ''}`;
        cell.addEventListener('click', () => toggleCell(i));
        fragment.appendChild(cell);
        return cell;
    });
    PICKER_GRID.textContent = '';
    PICKER_GRID.appendChild(fragment);
    const target = pickerCells[focusIndex] || pickerCells[0];
    if (target) target.focus({ preventScroll: true });
}

function openPicker(mode) {
    pickerMode = mode || 'rows';
    PICKER_TITLE.textContent = pickerMode === 'search' ? 'Rechercher dans' : 'Catégories';
    PICKER_HINT.textContent = pickerMode === 'search'
        ? 'OK : inclure / exclure de la recherche · Retour : valider'
        : 'OK : afficher / masquer · Retour : valider';
    PICKER.classList.remove('hidden');
    renderPicker(0);
    if (!categories.length) call('loadCategories');
}

function closePicker() {
    PICKER.classList.add('hidden');
    if (pickerMode === 'search') {
        applySearchFilter();
        buildRows();
        BTN_SCOPE.focus();
    } else {
        buildRows();
        BTN_ADD.focus();
    }
}

function toggleCell(i) {
    const item = pickerItems[i];
    if (!item) return;
    if (pickerMode === 'rows' && item.source === 'store') { openStoreEditor(); return; }
    const list = pickerMode === 'search' ? settings.searchCats : settings.sources;
    const idx = list.indexOf(item.source);
    if (idx >= 0) list.splice(idx, 1);
    else list.push(item.source);
    saveSettings();
    pickerCells[i].classList.toggle('checked', isChecked(item));
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
    const cell = pickerCells[j];
    if (cell && cell !== cur) cell.focus({ preventScroll: true });
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

function setStoreProfile(profile) {
    const changed = profile !== settings.profile;
    settings.profile = profile;
    const idx = settings.sources.indexOf('store');
    if (profile && idx < 0) settings.sources.push('store');
    if (!profile && idx >= 0) settings.sources.splice(idx, 1);
    if (changed) { delete rowData.store; rowLoading.store = false; }
    saveSettings();
}

function saveStore() {
    // Accepte « 325988 » ou un lien appteka.store/profile/325988
    const raw = STORE_INPUT.value.trim();
    const m = raw.match(/profile\/(\d+)/) || raw.match(/^(\d{1,10})$/);
    if (raw && !m) { STATUS.textContent = 'Numéro de profil invalide'; STORE_INPUT.focus(); return; }
    const profile = m ? m[1] : '';
    setStoreProfile(profile);
    STATUS.textContent = profile ? `Profil Store enregistré : ${profile}` : 'Profil Store retiré';
    closeStoreEditor();
}

document.getElementById('store-form').addEventListener('submit', (e) => { e.preventDefault(); saveStore(); });
window.TvStore = { setProfile: (p) => { setStoreProfile(String(p)); buildRows(); } };

// ------------------------------------------------------------ réglages

let account = { state: 'loading' };   // loading | ok | error
let auth = { step: 'email' };          // email | code | sending
let settingsMsg = { text: '', err: false };

let expandedSettings = null;
const ACCENTS = {
    default: { label: 'Couleur du skin' },
    green: { label: 'Vert', rgb: [121, 230, 58] },
    gold: { label: 'Or', rgb: [238, 193, 68] },
    blue: { label: 'Bleu', rgb: [81, 172, 255] },
    purple: { label: 'Violet', rgb: [192, 145, 255] },
    coral: { label: 'Corail', rgb: [255, 132, 120] },
};

function applySkin() {
    // « neu » (néumorphisme clair) se pose par-dessus les feuilles du skin néumorphique
    // les deux néumorphismes partagent neu.css ; le vert ajoute la palette sombre (neu-dark)
    const green = settings.skin === 'green' || settings.skin === 'neu';
    const neu = green;
    const root = document.documentElement;
    root.classList.toggle('green-skin', green);
    root.classList.toggle('neu-skin', neu);
    root.classList.toggle('neu-dark', settings.skin === 'green');
    const rgb = ACCENTS[settings.accent]?.rgb;
    root.classList.toggle('custom-accent', Boolean(rgb));
    for (const property of ['--accent-rgb', '--accent-light-rgb', '--accent-dark']) {
        root.style.removeProperty(property);
    }
    if (rgb) {
        root.style.setProperty('--accent-rgb', rgb.join(', '));
        root.style.setProperty('--accent-light-rgb', rgb.map(channel => Math.round(channel * 0.6 + 255 * 0.4)).join(', '));
        root.style.setProperty('--accent-dark', `rgb(${rgb.map(channel => Math.round(channel * 0.82)).join(', ')})`);
    }
    // La gélule originale conserve ses reflets chromés : on ne teinte que sa teinte verte
    // via une rotation de teinte (les gris neutres restent inchangés).
    // Source de référence : liquide vert (~120°), couleur de destination : accent actif.
    const activeAccent = rgb || getComputedStyle(root).getPropertyValue('--accent-rgb').trim().split(',').map(Number);
    if (activeAccent.length === 3 && activeAccent.every(Number.isFinite)) {
        const [r, g, b] = activeAccent.map(v => v / 255);
        const max = Math.max(r, g, b), min = Math.min(r, g, b), delta = max - min;
        let hue = 120;
        if (delta > 0) {
            if (max === r) hue = 60 * (((g - b) / delta) % 6);
            else if (max === g) hue = 60 * ((b - r) / delta + 2);
            else hue = 60 * ((r - g) / delta + 4);
        }
        root.style.setProperty('--capsule-hue-rotation', (((hue + 360) % 360) - 120).toFixed(1) + 'deg');
    }
    document.querySelectorAll('link.skin-classic').forEach(link => { link.media = green ? 'not all' : 'all'; });
    document.querySelectorAll('link.skin-green').forEach(link => { link.media = green ? 'all' : 'not all'; });
    document.querySelectorAll('link.skin-neu').forEach(link => { link.media = neu ? 'all' : 'not all'; });
}

function settingsSection(key, label, content) {
    const open = expandedSettings === key;
    return `<div class="set-section">
        <button class="btn focusable set-disclosure" type="button" data-k="section-${key}"
                data-section="${key}" aria-expanded="${open}" aria-controls="set-${key}">
            ${label}<span aria-hidden="true">⌄</span>
        </button>
        <div class="set-section-body" id="set-${key}" ${open ? '' : 'hidden'}>${content}</div>
    </div>`;
}

function openSettings() {
    expandedSettings = null;
    SETTINGS_PANEL.scrollTop = 0;
    SETTINGS.classList.remove('hidden');
    settingsMsg = { text: '', err: false };
    renderSettings();
    call('loadAccount');
}

function closeSettings() {
    SETTINGS.classList.add('hidden');
    BTN_SETTINGS.focus();
}

function renderSettings(focusKey) {
    const a = document.activeElement;
    const keep = focusKey || (a && SETTINGS_PANEL.contains(a) ? a.dataset.k : null);
    const values = {};
    SETTINGS_PANEL.querySelectorAll('input[data-k]').forEach(i => { values[i.dataset.k] = i.value; });

    const abis = TvArch.allowed();
    const chips = ALL_ABIS.map(([abi, label]) => `
        <button class="chip-toggle focusable ${abis.includes(abi) ? 'on' : ''}" data-k="abi-${abi}" data-abi="${abi}">
            <span class="dot"></span>${escapeHtml(label)}
            ${deviceAbis.includes(abi) ? '<span class="tag">cette TV</span>' : ''}
        </button>`).join('');

    SETTINGS_PANEL.innerHTML = `
        <div class="set-title">Paramètres</div>
        <div class="set-hint">${IS_MOBILE ? '' : 'Retour pour fermer'}</div>
        <button class="close-btn mobile-only" type="button" data-act="close-settings" aria-label="Fermer">✕</button>

        ${settingsSection('account', accountRegistered() ? 'Profil' : 'Connexion au compte Appteka', `
            ${accountHtml()}`)}
        ${settingsMsg.text ? `<div class="set-msg ${settingsMsg.err ? 'err' : ''}">${escapeHtml(settingsMsg.text)}</div>` : ''}
        ${settingsSection('language', 'Langue', `
            <div class="set-row" role="group" aria-label="Langue">
                ${[['auto', 'Automatique (langue de la TV)']].concat(window.TvI18n ? window.TvI18n.langs : []).map(([code, label]) => {
                    const cur = (window.TvI18n && window.TvI18n.choice) || 'auto';
                    return `<button type="button" class="chip-toggle focusable ${cur === code ? 'on' : ''}"
                            data-k="lang-${code}" data-lang="${code}" aria-pressed="${cur === code}">
                        <span class="dot" aria-hidden="true"></span><span ${code === 'auto' ? '' : 'data-no-i18n'}>${label}</span>
                    </button>`;
                }).join('')}
            </div>
            <div class="set-sub">L'interface et le contenu Appteka (catégories, descriptions) suivent la langue choisie.</div>`)}
        ${settingsSection('appearance', 'Apparence', `
            <div class="set-row set-inline" role="group" aria-label="Skins">
                <div class="set-label set-inline-label">Skins:</div>
                ${[['classic', 'Noir et or'], ['green', 'Neuro sombre'], ['neu', 'Neuro clair']].map(([id, label]) => `
                    <button type="button" class="chip-toggle focusable ${settings.skin === id ? 'on' : ''}"
                            data-k="skin-${id}" data-skin="${id}" aria-pressed="${settings.skin === id}">
                        <span class="dot" aria-hidden="true"></span>${label}
                    </button>`).join('')}
            </div>
            <div class="set-row set-inline set-spacing" role="group" aria-label="Accent Color">
                <div class="set-label set-inline-label" data-no-i18n>Accent Color:</div>
                ${Object.entries(ACCENTS).filter(([id]) => id !== 'default').map(([id, { label, rgb }]) => `
                    <button type="button" class="chip-toggle focusable ${settings.accent === id ? 'on' : ''}"
                            data-k="accent-${id}" data-accent="${id}" aria-pressed="${settings.accent === id}">
                        ${rgb ? `<span class="accent-swatch" style="background:rgb(${rgb.join(', ')})" aria-hidden="true"></span>` : ''}
                        ${label}
                    </button>`).join('')}
            </div>
            <div class="set-pair set-spacing">
                <div>
                    <div class="set-label">Taille des cartes</div>
                    <div class="set-row">
                        <button class="btn focusable" data-k="set-minus" data-act="minus">−</button>
                        <div class="set-value">${Math.round((settings.cardScale || 1) * 100)} %</div>
                        <button class="btn focusable" data-k="set-plus" data-act="plus">+</button>
                    </div>
                </div>
                <div>
                    <div class="set-label">Marges de l'écran</div>
                    <div class="set-row">
                        <button class="btn focusable" data-k="mar-minus" data-act="mar-minus">−</button>
                        <div class="set-value">${settings.margin == null ? 20 : settings.margin} px</div>
                        <button class="btn focusable" data-k="mar-plus" data-act="mar-plus">+</button>
                    </div>
                </div>
            </div>
            <div class="set-sub">Marges : baisse jusqu'à 0 si l'image paraît trop étroite ; monte si les bords sont coupés par ta TV.</div>`)}
        ${settingsSection('compatibility', 'Compatibilité APK', `
            <div class="set-row">${chips}
                <button class="chip-toggle focusable" data-k="abi-reset" data-act="abi-reset">↺ Cette TV</button>
            </div>
            <div class="set-sub">Les apps sans architecture compatible sont masquées dans les rangées et dans les versions
                d'une fiche. Cette TV : ${escapeHtml(deviceAbis.join(', '))}.</div>
        `)}
        ${IS_MOBILE ? '' : settingsSection('tvlink', 'Portables associés', tvLinkHtml())}
        ${settingsSection('update', 'Mise à jour', `
            <div class="set-label">Mise à jour Appteka TV</div>
            <div class="set-sub">Version installée : ${escapeHtml(String(call('installedUpdateVersion') || 'indisponible'))}</div>
            <div class="set-row" role="group" aria-label="Canal de mise à jour">
                <button class="btn focusable" type="button" data-k="self-update-stable" data-act="self-update" data-channel="stable">Stable</button>
                <button class="btn focusable" type="button" data-k="self-update-beta" data-act="self-update" data-channel="beta">Bêta</button>
                <button class="btn focusable" type="button" data-k="self-update-test" data-act="self-update" data-channel="test">Test</button>
            </div>
            <div class="set-sub">Choisir un canal pour rechercher une mise à jour.</div>`)}
        ${settingsSection('classic', 'Plus de fonctions', classicHtml())}
        <div class="set-section">
            <button class="btn focusable" type="button" data-k="export-config" data-act="export-config">
                Télécharger la configuration
            </button>
        </div>`;

    SETTINGS_PANEL.querySelectorAll('input[data-k]').forEach(i => { if (values[i.dataset.k] != null) i.value = values[i.dataset.k]; });
    SETTINGS_PANEL.querySelectorAll('[data-act], [data-abi], [data-section], [data-skin], [data-accent], [data-lang], [data-classic]').forEach(b => b.addEventListener('click', () => onSettingsAction(b)));
    SETTINGS_PANEL.querySelectorAll('form').forEach(f => f.addEventListener('submit', (e) => { e.preventDefault(); onSettingsSubmit(f.dataset.form); }));

    const target = (keep && SETTINGS_PANEL.querySelector(`[data-k="${keep}"]`)) || SETTINGS_PANEL.querySelector('.focusable');
    if (target) target.focus({ preventScroll: true });
}

function accountRegistered() {
    return account.state === 'ok' && Boolean(account.profile && account.profile.registered);
}

function accountHtml() {
    if (account.state === 'loading') return '<div class="set-sub">Chargement du profil…</div>';
    const p = account.profile || {};
    let html = '';
    if (account.state === 'ok') {
        const initial = (p.name || '?').trim().charAt(0).toUpperCase() || '?';
        html += `
            <div class="account-card">
                <div class="account-avatar">${escapeHtml(initial)}</div>
                <div>
                    <div class="account-name">${escapeHtml(p.name || 'Invité')} <span style="color:var(--muted);font-size:13px">#${p.userId}</span></div>
                    <div class="account-meta">${p.registered ? escapeHtml(p.email || 'Compte enregistré') : 'Compte invité (non connecté)'}${p.verified ? ' · vérifié' : ''}</div>
                    <div class="account-stats">
                        <div><b>${p.files || 0}</b><span>apps publiées</span></div>
                        <div><b>${p.favorites || 0}</b><span>favoris</span></div>
                        <div><b>${p.downloads || 0}</b><span>téléchargements</span></div>
                        <div><b>${p.reviews || 0}</b><span>avis</span></div>
                    </div>
                </div>
            </div>
            <div class="set-row" style="margin-top:14px">
                <button class="btn focusable" data-k="acc-store" data-act="my-store">Mon Store</button>
                <button class="btn focusable" data-k="acc-use" data-act="use-store">Afficher mon Store sur l'accueil</button>
            </div>`;
    } else {
        html += '<div class="set-sub">Profil indisponible (réseau ?).</div>';
    }
    if (!p.registered || auth.open) html += loginHtml();
    else html += '<div class="set-row" style="margin-top:10px"><button class="btn focusable" data-k="acc-switch" data-act="switch">Se connecter à un autre compte</button></div>';
    return html;
}

function loginHtml() {
    if (auth.step === 'code') {
        return `
            <div class="set-sub" style="margin:14px 0 8px">Code envoyé à ${escapeHtml(auth.email)}. Saisis-le ci-dessous.</div>
            <form class="set-row" data-form="code" autocomplete="off">
                <input class="set-input focusable" data-k="auth-code" inputmode="numeric" enterkeyhint="done" placeholder="Code reçu par e-mail">
                ${auth.registered ? '' : '<input class="set-input focusable" data-k="auth-name" enterkeyhint="done" placeholder="Ton pseudo (nouveau compte)">'}
                <button class="btn primary focusable" data-k="auth-verify" type="submit">Valider</button>
                <button class="btn focusable" data-k="auth-cancel" type="button" data-act="auth-cancel">Annuler</button>
            </form>`;
    }
    return `
        <div class="set-sub" style="margin:14px 0 8px">Connexion par e-mail : un code de connexion t'est envoyé.</div>
        <form class="set-row" data-form="email" autocomplete="off">
            <input class="set-input focusable" data-k="auth-email" type="email" enterkeyhint="send" placeholder="Adresse e-mail">
            <button class="btn primary focusable" data-k="auth-send" type="submit">${auth.step === 'sending' ? 'Envoi…' : 'Recevoir le code'}</button>
        </form>`;
}

// Liaison portable -> TV (affiché sur la TV)
function tvLinkHtml() {
    let info = {};
    try { info = JSON.parse((window.Android && window.Android.tvLinkInfo && window.Android.tvLinkInfo()) || '{}'); } catch (e) { /* */ }
    const devices = info.devices || [];
    return `
        <div class="set-sub">Pour associer un nouveau portable, utilise le bouton QR en haut de l'accueil.</div>
        <div class="set-row" style="margin-top:10px">
            <div class="set-value" data-no-i18n style="min-width:0">${escapeHtml(info.name || '')}</div>
            <div class="set-sub" data-no-i18n style="margin:0">${info.ip ? escapeHtml(info.ip + (info.port && info.port !== 8336 ? ':' + info.port : '')) : ''}</div>
        </div>
        <div class="set-label set-spacing">Appareils associés</div>
        ${devices.length ? devices.map(device => `
            <div class="set-row">
                <span data-no-i18n>${escapeHtml(device.name)}${pairedSince(device.added)}${device.files === 'true' ? ' · 📁' : ''}</span>
                ${device.files === 'true' ? `<button class="btn focusable" type="button" data-k="files-${escapeHtml(device.id)}"
                        data-act="tvlink-revoke-files" data-device="${escapeHtml(device.id)}">Retirer l'accès fichiers</button>` : ''}
                <button class="btn focusable" type="button" data-k="forget-${escapeHtml(device.id)}"
                        data-act="tvlink-forget" data-device="${escapeHtml(device.id)}">Oublier</button>
            </div>`).join('') + `
            <button class="btn focusable" type="button" data-k="tvlink-forget-all" data-act="tvlink-forget-all">Tout oublier</button>` : '—'}
        ${filesLogHtml(info.filesLog || [])}`;
}

// ATV-09 : dernières opérations faites par les téléphones sur les fichiers de la TV
function filesLogHtml(log) {
    if (!log.length) return '';
    const ops = { mkdir: '📁+', rename: '✏️', delete: '🗑️', push: '⬇️', pull: '⬆️', install: '📦' };
    return `
        <div class="set-label set-spacing">Fichiers : dernières opérations</div>
        ${log.slice(0, 15).map(item => {
            let when = '';
            try { when = new Date(Number(item.at)).toLocaleString(); } catch (e) { /* */ }
            return `<div class="set-sub" data-no-i18n style="margin:2px 0;word-break:break-all">${ops[item.op] || '•'} ${escapeHtml(item.path || '')} · ${escapeHtml(item.device || '')} · ${escapeHtml(when)}</div>`;
        }).join('')}`;
}

// Date d'association affichée à côté du téléphone (ATV-01 durcissement)
function pairedSince(added) {
    const ms = Number(added);
    if (!ms) return '';
    try { return ' · ' + escapeHtml(new Date(ms).toLocaleDateString()); } catch (e) { return ''; }
}

// Connecteurs vers l'interface classique (fonctions pas encore refaites en HTML)
const CLASSIC = [
    ['upload', 'Publier un APK', false],
    ['uploads', 'Mes publications', true],
    ['downloads', 'Mes téléchargements', true],
    ['installed', 'Apps installées', false],
    ['favorites', 'Favoris du compte', true],
    ['reviews', 'Mes avis', true],
    ['feed', 'Fil d\u2019actualité', true],
    ['profile', 'Mon profil', true],
    ['home', 'Discussions et accueil classique', false],
    ['moderation', 'Modération', false],
    ['settings', 'Réglages avancés', false],
    ['about', 'À propos', false],
];
function classicHtml() {
    const uid = account.profile && account.profile.userId;
    return `
        <div class="set-sub">Ces écrans s'ouvrent encore dans l'interface d'origine, en attendant leur version HTML.</div>
        <div class="set-row" style="margin-top:10px">${CLASSIC.map(([id, label, needsUser]) => `
            <button class="btn focusable" type="button" data-k="classic-${id}" data-classic="${id}"
                    ${needsUser && !uid ? 'disabled' : ''}>${label}</button>`).join('')}
        </div>`;
}

function onSettingsAction(b) {
    if (b.dataset.act === 'self-update') {
        const channel = ['stable', 'beta', 'test'].includes(b.dataset.channel) ? b.dataset.channel : 'stable';
        settingsMsg = { text: 'Recherche de mise à jour (' + channel + ') lancée…', err: false };
        call('checkSelfUpdate', channel);
        renderSettings('self-update-' + channel);
        return;
    }
    const { section, skin, accent } = b.dataset;
    if (b.dataset.classic) {
        const uid = account.profile && account.profile.userId;
        call('openClassic', b.dataset.classic, uid || '');
        return;
    }
    if (b.dataset.lang) {
        if (window.TvI18n && b.dataset.lang !== window.TvI18n.choice) window.TvI18n.setLang(b.dataset.lang);
        return;
    }
    if (section) {
        expandedSettings = expandedSettings === section ? null : section;
        renderSettings();
        return;
    }
    if (skin || accent) {
        if (skin) settings.skin = skin;
        if (accent) settings.accent = accent;
        applySkin();
        saveSettings();
        renderSettings();
        return;
    }
    const act = b.dataset.act;
    if (b.dataset.abi) {
        const list = TvArch.allowed().slice();
        const i = list.indexOf(b.dataset.abi);
        if (i >= 0) list.splice(i, 1); else list.push(b.dataset.abi);
        settings.archs = list;
        saveSettings();
        refreshAllRows();
        renderSettings();
        return;
    }
    switch (act) {
        case 'close-settings':
            closeSettings();
            return;
        case 'tvlink-pair':
            call('tvLinkShowQr');
            return;
        case 'tvlink-forget':
            call('tvLinkForget', b.dataset.device);
            setTimeout(() => { updatePairButton(); renderSettings('section-tvlink'); }, 200);
            return;
        case 'tvlink-revoke-files':
            call('tvLinkRevokeFiles', b.dataset.device);
            setTimeout(() => renderSettings('section-tvlink'), 200);
            return;
        case 'tvlink-forget-all':
            call('tvLinkForgetAll');
            setTimeout(() => { updatePairButton(); renderSettings('section-tvlink'); }, 200);
            return;
        case 'export-config': {
            const { skin, accent, cardScale, margin, archs, sources, searchCats, profile } = settings;
            const json = JSON.stringify({
                version: 1,
                settings: { skin, accent, cardScale, margin, archs, sources, searchCats, profile },
            }, null, 2);
            if (window.Android && typeof window.Android.exportConfig === 'function') {
                call('exportConfig', json);
            } else {
                const url = URL.createObjectURL(new Blob([json], { type: 'application/json' }));
                const link = document.createElement('a');
                link.href = url;
                link.download = 'appteka-tv-config.json';
                link.click();
                setTimeout(() => URL.revokeObjectURL(url), 1000);
            }
            break;
        }
        case 'minus':
        case 'plus':
            settings.cardScale = Math.round(Math.min(1.5, Math.max(0.7, (settings.cardScale || 1) + (act === 'plus' ? 0.1 : -0.1))) * 10) / 10;
            saveSettings();
            applyCardScale();
            renderSettings();
            break;
        case 'mar-minus':
        case 'mar-plus': {
            const cur = settings.margin == null ? 20 : settings.margin;
            settings.margin = Math.min(60, Math.max(0, cur + (act === 'mar-plus' ? 10 : -10)));
            saveSettings();
            applyCardScale();
            renderSettings();
            break;
        }
        case 'abi-reset':
            settings.archs = null;
            saveSettings();
            refreshAllRows();
            renderSettings();
            break;
        case 'my-store':
            if (account.profile) window.TvAuthor.open(account.profile.userId, account.profile.name || 'Mon Store');
            break;
        case 'use-store':
            if (account.profile) {
                window.TvStore.setProfile(account.profile.userId);
                settingsMsg = { text: 'Ton Store est ajouté à l\'accueil.', err: false };
                renderSettings();
            }
            break;
        case 'switch':
            auth = { step: 'email', open: true };
            renderSettings('auth-email');
            break;
        case 'auth-cancel':
            auth = { step: 'email', open: auth.open };
            settingsMsg = { text: '', err: false };
            renderSettings('auth-email');
            break;
    }
}

function onSettingsSubmit(form) {
    if (form === 'email') {
        const email = (SETTINGS_PANEL.querySelector('[data-k="auth-email"]').value || '').trim();
        if (!/^\S+@\S+\.\S+$/.test(email)) { settingsMsg = { text: 'Adresse e-mail invalide', err: true }; renderSettings('auth-email'); return; }
        auth = Object.assign({}, auth, { step: 'sending', email });
        settingsMsg = { text: '', err: false };
        renderSettings('auth-send');
        call('requestLoginCode', email);
    } else if (form === 'code') {
        const code = (SETTINGS_PANEL.querySelector('[data-k="auth-code"]').value || '').trim();
        const nameEl = SETTINGS_PANEL.querySelector('[data-k="auth-name"]');
        const name = nameEl ? nameEl.value.trim() : '';
        if (!code) { settingsMsg = { text: 'Saisis le code reçu', err: true }; renderSettings('auth-code'); return; }
        if (!auth.registered && !name) { settingsMsg = { text: 'Choisis un pseudo', err: true }; renderSettings('auth-name'); return; }
        settingsMsg = { text: 'Vérification…', err: false };
        renderSettings('auth-verify');
        call('verifyLoginCode', auth.requestId, code, name);
    }
}

window.onAccount = function (json) {
    const p = typeof json === 'string' ? JSON.parse(json) : json;
    account = { state: 'ok', profile: p };
    if (!SETTINGS.classList.contains('hidden')) renderSettings();
};
window.onAccountError = function () {
    account = { state: 'error' };
    if (!SETTINGS.classList.contains('hidden')) renderSettings();
};
window.onAuthCode = function (json) {
    const r = typeof json === 'string' ? JSON.parse(json) : json;
    auth = Object.assign({}, auth, { step: 'code', requestId: r.requestId, registered: r.registered });
    settingsMsg = { text: r.registered ? 'Compte trouvé, code envoyé.' : 'Nouveau compte : choisis aussi un pseudo.', err: false };
    if (!SETTINGS.classList.contains('hidden')) renderSettings('auth-code');
};
window.onAuthDone = function () {
    auth = { step: 'email' };
    settingsMsg = { text: 'Connecté ✔', err: false };
    account = { state: 'loading' };
    if (!SETTINGS.classList.contains('hidden')) renderSettings('acc-store');
};
window.onAuthError = function (json) {
    const r = typeof json === 'string' ? JSON.parse(json) : json;
    if (auth.step === 'sending') auth.step = 'email';
    settingsMsg = { text: r.message || 'Erreur', err: true };
    if (!SETTINGS.classList.contains('hidden')) renderSettings();
};

// ------------------------------------------------------------ navigation

function headerMove(focused, dir) {
    // Les boutons masqués (ex. mode portable) sont sautés
    const items = HEADER_ITEMS.filter(el => el.getClientRects().length > 0);
    const i = items.indexOf(focused);
    const j = i + (dir === 'right' ? 1 : -1);
    if (j >= 0 && j < items.length) items[j].focus();
}

// Bouton QR de l'accueil : toujours visible, même après une association, pour pouvoir
// associer d'autres appareils (la TV accepte plusieurs portables ; chaque appui crée une
// nouvelle invitation). Annule le choix de la 2164 (bouton masqué tant qu'un portable était associé).
function updatePairButton() {
    if (!BTN_PAIR) return;
    BTN_PAIR.classList.remove('hidden-paired');
}

function onHeaderEnter(el) {
    if (el === SEARCH_BOX) startEditing();
    else if (el === BTN_FUN) { if (window.playGravitySplash) window.playGravitySplash(true); }
    else if (el === BTN_PAIR) call('tvLinkShowQr');
    else if (el === BTN_SCOPE) openPicker('search');
    else if (el === BTN_ADD) openPicker('rows');
    else if (el === BTN_SETTINGS) openSettings();
    else if (el === BTN_EXIT) exitApp();
}

function exitApp() {
    if (window.Android && typeof window.Android.exit === 'function') window.Android.exit();
}

function onRowIconKey(e, icon) {
    const row = icon.closest('.row');
    const key = row.dataset.key;

    if (movingKey === key) {
        if (e.key === 'ArrowUp') { e.preventDefault(); moveRow(-1); }
        else if (e.key === 'ArrowDown') { e.preventDefault(); moveRow(1); }
        else if (e.key === 'Enter' || e.key === 'Escape') { e.preventDefault(); if (!e.repeat) endMove(); }
        else if (e.key.startsWith('Arrow')) e.preventDefault();
        return;
    }

    if (e.key === 'Enter') {
        e.preventDefault();
        // Appui long : touche maintenue (répétitions) ou relâchée après le délai
        if (e.repeat) { clearTimeout(pressTimer); pressTimer = null; startMove(key); return; }
        clearTimeout(pressTimer);
        pressTimer = setTimeout(() => { pressTimer = null; startMove(key); }, LONG_PRESS_MS);
        return;
    }
    if (e.key === 'ArrowRight') { e.preventDefault(); const c = row.querySelector('.app-card'); if (c) focusCard(c); }
    else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        e.preventDefault();
        const rows = Array.from(ROWS.querySelectorAll('.row'));
        const r = rows.indexOf(row) + (e.key === 'ArrowDown' ? 1 : -1);
        if (r >= 0 && r < rows.length) {
            rows[r].querySelector('.row-icon').focus({ preventScroll: true });
            rows[r].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } else if (r < 0) focusSearchBox();
    } else if (e.key === 'ArrowLeft') e.preventDefault();
}

// Portable : appui long = favori (carte) ou déplacement (icône de rangée), flèches cliquables
function setupTouch() {
    document.addEventListener('contextmenu', (e) => {
        const card = e.target.closest && e.target.closest('.app-card');
        const icon = e.target.closest && e.target.closest('.row-icon');
        if (card && card._app) { e.preventDefault(); TvFavorites.toggle(card._app); }
        else if (icon && !movingKey) { e.preventDefault(); startMove(icon.closest('.row').dataset.key); }
    });
    ROWS.addEventListener('click', (e) => {
        const arrow = e.target.closest('.row-arrow');
        if (arrow && movingKey) { e.stopPropagation(); moveRow(arrow.classList.contains('up') ? -1 : 1); return; }
        const icon = e.target.closest('.row-icon');
        if (icon && movingKey) endMove();
    });
    SEARCH_BOX.addEventListener('click', () => { if (document.activeElement !== SEARCH_INPUT) startEditing(); });
    document.getElementById('picker-close').addEventListener('click', closePicker);
}

function setupFocusManagement() {
    setupTouch();
    SEARCH_BOX.addEventListener('submit', (e) => { e.preventDefault(); submitSearch(); });
    HEADER_ITEMS.forEach(el => { if (el !== SEARCH_BOX) el.addEventListener('click', () => onHeaderEnter(el)); });
    updatePairButton();

    // Fin d'un appui court sur une icône de rangée : va à la 1re carte
    document.addEventListener('keyup', (e) => {
        if (e.key !== 'Enter' || !pressTimer) return;
        clearTimeout(pressTimer);
        pressTimer = null;
        const a = document.activeElement;
        if (a && a.classList.contains('row-icon')) {
            const c = a.closest('.row').querySelector('.app-card');
            if (c) focusCard(c);
        }
    });

    document.addEventListener('keydown', (e) => {
        // Fiche app, store d'un auteur, visionneuse
        if (window.TvDetails && window.TvDetails.handleKey(e)) return;
        if (window.TvDetails && window.TvDetails.isOpen()) return;

        // Réglages
        if (!SETTINGS.classList.contains('hidden')) {
            const dir = TvNav.DIRS[e.key];
            if (dir) {
                if (TvNav.inputKeepsKey(e)) return;
                e.preventDefault();
                TvNav.move(SETTINGS_PANEL, dir);
            } else if (e.key === 'Escape') { e.preventDefault(); closeSettings(); }
            else if (e.key === 'Enter') {
                const a = document.activeElement;
                if (a && a.tagName === 'INPUT') {
                    e.preventDefault();
                    const f = a.closest('form');
                    if (f) onSettingsSubmit(f.dataset.form);
                }
            }
            return;
        }

        // Fenêtre profil Store : champ ↔ bouton
        if (!STORE_EDITOR.classList.contains('hidden')) {
            const a = document.activeElement;
            if (e.key === 'Escape') { e.preventDefault(); closeStoreEditor(); }
            else if ((e.key === 'ArrowRight' || e.key === 'ArrowDown') && a === STORE_INPUT && !TvNav.inputKeepsKey(e)) { e.preventDefault(); STORE_SAVE.focus(); }
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
                if (a && a.classList.contains('cell') && !e.repeat) toggleCell(Number(a.dataset.index));
            } else if (e.key === 'Escape') { e.preventDefault(); closePicker(); }
            return;
        }

        const focused = document.activeElement;

        // Icône de rangée (focus, appui long = déplacement)
        if (focused && focused.classList.contains('row-icon')) { onRowIconKey(e, focused); return; }

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

        // En-tête
        if (HEADER_ITEMS.includes(focused)) {
            if (e.key === 'Enter') { e.preventDefault(); onHeaderEnter(focused); }
            else if (e.key === 'ArrowRight') { e.preventDefault(); headerMove(focused, 'right'); }
            else if (e.key === 'ArrowLeft') { e.preventDefault(); headerMove(focused, 'left'); }
            else if (e.key === 'ArrowDown') { e.preventDefault(); const c = firstCard(); c ? focusCard(c) : (ROWS.querySelector('.row-icon') || BTN_ADD).focus(); }
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
        else if (e.key === 'ArrowLeft') {
            e.preventDefault();
            if (idx > 0) focusCard(cards[idx - 1]);
            else focused.closest('.row').querySelector('.row-icon').focus({ preventScroll: true });
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
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

// Appui long sur OK (détecté côté Android) : déplacer une rangée ou mettre une app en favori.
window.onTvLongPress = function () {
    const a = document.activeElement;
    if (!a) return;
    if (a.classList.contains('row-icon') && !movingKey) {
        startMove(a.closest('.row').dataset.key);
    } else if (a.classList.contains('app-card') && a._app) {
        const row = a.closest('.row');
        TvFavorites.toggle(a._app);
        // la sélection reste sur la même carte (ou sa remplaçante dans la même rangée)
        const same = document.body.contains(a) ? a
            : row && row.querySelector(`.app-card[data-id="${CSS.escape(String(a._app.id))}"]`);
        const target = same || (row && row.querySelector('.app-card')) || firstCard();
        if (target) target.focus({ preventScroll: true });
    }
};

// Touche Retour (appelée par Android). Renvoie true si géré ici.
let pairTimer = null;
window.onPaired = function (json) {
    const o = typeof json === 'string' ? JSON.parse(json) : json;
    PAIR_MODAL.classList.add('hidden');
    STATUS.textContent = `Portable associé : ${o.device}`;
    // ATV (vraie TV, 24/11) : la liste « Appareils associés » restait sur « — » tant que les
    // réglages n'étaient pas rouverts ; on la redessine dès que l'association est acceptée.
    if (!SETTINGS.classList.contains('hidden')) renderSettings();
    updatePairButton();
};
// Un portable associé envoie une app : la fiche s'ouvre (et l'installation démarre si demandé)
window.onRemoteOpen = function (json) {
    const o = typeof json === 'string' ? JSON.parse(json) : json;
    PAIR_MODAL.classList.add('hidden');
    if (!PICKER.classList.contains('hidden')) closePicker();
    if (!SETTINGS.classList.contains('hidden')) closeSettings();
    if (!STORE_EDITOR.classList.contains('hidden')) closeStoreEditor();
    window.TvDetails.open(o.id, null);
    STATUS.textContent = o.install ? `Installation demandée par ${o.device}` : `Envoyé par ${o.device}`;
};

// Retour (Gonzo 24/11) : depuis n'importe quel écran, retour direct à l'accueil.
// Déjà sur l'accueil : renvoie false et Android gère le double appui pour quitter.
window.onTvBack = function () {
    let moved = false;
    if (!PAIR_MODAL.classList.contains('hidden')) { PAIR_MODAL.classList.add('hidden'); moved = true; }
    if (window.TvDetails && window.TvDetails.closeAll && window.TvDetails.closeAll()) moved = true;
    if (endMove()) moved = true;
    if (!SETTINGS.classList.contains('hidden')) { closeSettings(); moved = true; }
    if (!STORE_EDITOR.classList.contains('hidden')) { closeStoreEditor(); moved = true; }
    if (!PICKER.classList.contains('hidden')) { closePicker(); moved = true; }
    if (closeSearch()) moved = true;
    const focused = document.activeElement;
    if (focused === SEARCH_INPUT) { SEARCH_INPUT.blur(); moved = true; }
    const c = firstCard();
    const atHome = !focused || focused === document.body || focused === c || HEADER_ITEMS.includes(focused);
    if (!moved && atHome) return false;
    ROWS.scrollTop = 0;
    c ? focusCard(c) : focusSearchBox();
    return true;
};

// ------------------------------------------------------------ appels Android

window.onCategories = function (json) {
    try {
        const list = typeof json === 'string' ? JSON.parse(json) : json;
        categories = (list || []).map(c => ({ id: Number(c.id), name: c.name, icon: c.icon }));
    } catch (e) { categories = []; }
    ROWS.querySelectorAll('.row').forEach(row => {
        const ic = iconFor(row.dataset.key);
        const holder = row.querySelector('.row-icon');
        holder.title = ic.name;
        const svg = holder.querySelector('.icon-svg');
        svg.className = 'icon-svg ' + ic.cls;
        svg.innerHTML = ic.html;
    });
    updateScopeBadge();
    if (!PICKER.classList.contains('hidden') && STORE_EDITOR.classList.contains('hidden')) {
        const a = document.activeElement;
        renderPicker(a && a.dataset.index ? Number(a.dataset.index) : 0);
    }
};

window.loadTvAppsJson = function (json) {
    try {
        const o = typeof json === 'string' ? JSON.parse(json) : json;
        if (o.key.indexOf('author:') === 0) { if (window.TvAuthor) window.TvAuthor.onApps(o.key, o.apps || []); return; }
        rowData[o.key] = o.apps || [];
        rowLoading[o.key] = false;
        fillRow(o.key);
        updateStatus();
    } catch (e) {
        console.error('Failed to parse apps', e);
    }
};

// ATV (Gonzo 25/11) : cause de l'échec affichée dans la rangée + nouvel essai automatique.
window.onCategoryError = function (key, reason) {
    if (key.indexOf('author:') === 0) { if (window.TvAuthor) window.TvAuthor.onError(key); return; }
    rowLoading[key] = false;
    rowError[key] = String(reason || '').replace(/[^A-Za-z0-9_]/g, '').slice(0, 40);
    rowRetries[key] = rowRetries[key] || 0;
    if (rowData[key] === undefined) rowData[key] = null;
    fillRow(key);
    if (rowRetries[key] < ROW_MAX_RETRIES) {
        const attempt = ++rowRetries[key];
        setTimeout(() => {
            if (rowData[key] !== null) return;
            rowData[key] = undefined;
            fillRow(key);
            ensureLoaded(key);
        }, 4000 * attempt);
    }
};

window.onSearchResults = function (json) {
    try {
        const apps = typeof json === 'string' ? JSON.parse(json) : json;
        const first = searchRaw === undefined;
        searchRaw = apps || [];
        applySearchFilter();
        fillRow('search');
        const row = rowEl('search');
        const card = row && row.querySelector('.app-card');
        if (first && card && !overlayOpen()) focusCard(card);
        updateStatus();
    } catch (e) {
        searchRaw = null;
        rowData.search = null;
        fillRow('search');
    }
};

window.onSearchError = function () {
    searchRaw = null;
    rowData.search = null;
    fillRow('search');
};

window.onDownloadStarted = function (json) {
    let name = json;
    try { const o = JSON.parse(json); name = o.name || o.id; } catch (e) { /* texte brut */ }
    STATUS.textContent = `Téléchargement lancé : ${name} — installation automatique à la fin`;
};

// Les cartes sont réutilisées dans la fiche et le store d'un auteur
window.TvCards = { create: createAppCard };

function start() {
    applySkin();
    applyCardScale();
    setupFocusManagement();
    buildRows();
    call('loadCategories');
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
else start();

// ------------------------------------------------------------ clavier à l'écran
// ATV (Gonzo 25/11) : un clavier personnalisé occupe la moitié basse de l'écran sans
// redimensionner la page. Dès qu'un champ de saisie prend le focus, le champ ET son bouton
// (son formulaire / sa ligne) sont amenés dans la moitié haute : défilement du panneau
// (avec une marge basse ajoutée pour pouvoir défiler assez), sinon translation de la boîte.
(function keyboardLift() {
    const TOP = 0.08;     // haut visé : 8 % de la hauteur de l'écran
    const LIMIT = 0.48;   // le bas du bloc doit rester au-dessus de 48 %
    let lifted = null;    // {scroller, box}
    let dropTimer = null;

    function isField(el) {
        return el && (el.tagName === 'TEXTAREA' ||
            (el.tagName === 'INPUT' && !['button', 'submit', 'checkbox', 'radio', 'range'].includes(el.type)));
    }
    function scrollerOf(el) {
        for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
            const oy = getComputedStyle(p).overflowY;
            if (oy === 'auto' || oy === 'scroll') return p;
        }
        return null;
    }
    function boxOf(el) {
        return el.closest('.settings-panel, .modal-box') || null;
    }
    function drop() {
        if (!lifted) return;
        if (lifted.scroller) lifted.scroller.classList.remove('kb-lift');
        if (lifted.box) lifted.box.style.transform = '';
        lifted = null;
    }
    function lift(el) {
        clearTimeout(dropTimer);
        const block = el.closest('form, .set-row, .modal-row') || el;
        const h = window.innerHeight || document.documentElement.clientHeight;
        const scroller = scrollerOf(el);
        const box = boxOf(el);
        if (lifted && (lifted.scroller !== scroller || lifted.box !== box)) drop();
        lifted = { scroller, box };
        if (scroller) {
            scroller.classList.add('kb-lift');
            const r = block.getBoundingClientRect();
            scroller.scrollTop += r.top - h * TOP;
        }
        // Panneau trop court ou sans défilement : on remonte la boîte elle-même.
        const r2 = block.getBoundingClientRect();
        if (box && r2.bottom > h * LIMIT) {
            const cur = new DOMMatrixReadOnly(getComputedStyle(box).transform).m42 || 0;
            box.style.transform = `translateY(${Math.round(cur - (r2.top - h * TOP))}px)`;
        }
    }
    document.addEventListener('focusin', e => {
        const el = e.target;
        if (!isField(el)) {
            // Focus sur le bouton du même bloc (« Recevoir le code ») : garder la position.
            if (lifted && el && el.closest && el.closest('form, .set-row, .modal-row') &&
                el.closest('form, .set-row, .modal-row').querySelector('input, textarea')) return;
            dropTimer = setTimeout(drop, 150);
            return;
        }
        if (el.id === 'search-input') return; // déjà tout en haut
        lift(el);
        // Le clavier et le re-rendu des Paramètres arrivent après le focus : on recale.
        setTimeout(() => { if (document.activeElement === el) lift(el); }, 350);
    });
    document.addEventListener('focusout', () => {
        dropTimer = setTimeout(() => { if (!isField(document.activeElement)) drop(); }, 250);
    });
})();
