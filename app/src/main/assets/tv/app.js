// Appteka TV - Interface logic
// Rangées : "tv" (catégorie 37) et "entertainment" (catégorie 27)

const STATUS = document.getElementById('status');
const ROW_KEYS = ['tv', 'entertainment'];

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
                <button class="btn-download" type="button" tabindex="-1">Télécharger</button>
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
    STATUS.textContent = `Détails : ${app.name}`;
    if (window.Android && typeof window.Android.openAppDetails === 'function') {
        window.Android.openAppDetails(String(app.id));
    }
}

function onDownload(app) {
    STATUS.textContent = `Téléchargement : ${app.name}…`;
    if (window.Android && typeof window.Android.downloadApp === 'function') {
        window.Android.downloadApp(String(app.id));
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
    } else if (!document.activeElement || !document.activeElement.classList.contains('app-card')) {
        // Premier chargement : focus sur la 1re carte de la 1re rangée remplie
        for (const k of ROW_KEYS) {
            const first = rowEl(k) && rowEl(k).querySelector('.app-card');
            if (first) { focusCard(first); break; }
        }
    }
    updateStatus();
}

function updateStatus() {
    const total = ROW_KEYS.reduce((n, k) => n + (rowEl(k) ? rowEl(k).querySelectorAll('.app-card').length : 0), 0);
    STATUS.textContent = `${total} applications · ◀ ▶ ▲ ▼ naviguer · OK = détails · D = télécharger`;
}

function setupFocusManagement() {
    document.addEventListener('keydown', (e) => {
        const focused = document.activeElement;
        if (!focused || !focused.classList.contains('app-card')) {
            // Rien de sélectionné : n'importe quelle flèche reprend le focus
            if (e.key.startsWith('Arrow')) {
                const first = document.querySelector('.app-card');
                if (first) { e.preventDefault(); focusCard(first); }
            }
            return;
        }
        const row = focused.parentElement;
        const cards = Array.from(row.querySelectorAll('.app-card'));
        const idx = cards.indexOf(focused);

        if (e.key === 'ArrowRight') {
            e.preventDefault();
            focusCard(cards[idx + 1]);
        } else if (e.key === 'ArrowLeft') {
            e.preventDefault();
            focusCard(cards[idx - 1]);
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const r = ROW_KEYS.indexOf(row.dataset.key);
            const step = e.key === 'ArrowDown' ? 1 : -1;
            for (let i = r + step; i >= 0 && i < ROW_KEYS.length; i += step) {
                const target = Array.from(rowEl(ROW_KEYS[i]).querySelectorAll('.app-card'));
                if (target.length) {
                    focusCard(target[Math.min(idx, target.length - 1)]);
                    break;
                }
            }
        }
    });
}

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

window.onDownloadStarted = function(appId) {
    STATUS.textContent = `Téléchargement lancé (${appId})`;
};

document.addEventListener('DOMContentLoaded', setupFocusManagement);
