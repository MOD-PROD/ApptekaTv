// Appteka TV - Interface logic

const APPS_CONTAINER = document.getElementById('apps-container');
const STATUS = document.getElementById('status');

const MOCK_TV_APPS = [
    { id: 1, name: "IPTV Pro", icon: "", downloads: "12k", size: "8.2 MB" },
    { id: 2, name: "TiviMate", icon: "", downloads: "45k", size: "15 MB" },
    { id: 3, name: "VLC", icon: "", downloads: "120k", size: "32 MB" },
    { id: 4, name: "Kodi", icon: "", downloads: "89k", size: "48 MB" },
    { id: 5, name: "SmartTube", icon: "", downloads: "67k", size: "22 MB" },
    { id: 6, name: "MX Player", icon: "", downloads: "210k", size: "18 MB" }
];

function createAppCard(app) {
    const card = document.createElement('div');
    card.className = 'app-card';
    card.tabIndex = 0;
    card.dataset.id = app.id;

    const iconHtml = app.icon
        ? `<img class="app-icon" src="${app.icon}" alt="" loading="lazy" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';">
           <div class="app-icon app-icon-fallback" style="display:none;align-items:center;justify-content:center;font-size:42px;background:#1e293b;">📺</div>`
        : `<div class="app-icon" style="display:flex;align-items:center;justify-content:center;font-size:42px;background:#1e293b;">📺</div>`;

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
        console.log('downloadApp not available', app.id);
        STATUS.textContent = 'Bridge téléchargement indisponible';
    }
}

function renderApps(apps) {
    APPS_CONTAINER.innerHTML = '';

    if (!apps || apps.length === 0) {
        APPS_CONTAINER.innerHTML = '<div class="loading">Aucune application TV trouvée</div>';
        STATUS.textContent = '0 application';
        return;
    }

    apps.forEach(app => {
        APPS_CONTAINER.appendChild(createAppCard(app));
    });

    const firstCard = APPS_CONTAINER.querySelector('.app-card');
    if (firstCard) firstCard.focus();

    STATUS.textContent = `${apps.length} applications TV · OK = détails · D = télécharger`;
}

function setupFocusManagement() {
    document.addEventListener('keydown', (e) => {
        const focused = document.activeElement;
        if (!focused || !focused.classList.contains('app-card')) return;

        if (e.key === 'ArrowRight') {
            const next = focused.nextElementSibling;
            if (next) {
                next.focus();
                next.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
            }
        } else if (e.key === 'ArrowLeft') {
            const prev = focused.previousElementSibling;
            if (prev) {
                prev.focus();
                prev.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
            }
        }
    });
}

window.loadTvApps = function(appsJson) {
    try {
        const apps = typeof appsJson === 'string' ? JSON.parse(appsJson) : appsJson;
        renderApps(apps);
    } catch (e) {
        console.error('Failed to parse apps', e);
        STATUS.textContent = 'Erreur de chargement';
    }
};

window.onDownloadStarted = function(appId) {
    STATUS.textContent = `Téléchargement lancé (${appId})`;
};

document.addEventListener('DOMContentLoaded', () => {
    setupFocusManagement();
    setTimeout(() => {
        if (APPS_CONTAINER.querySelector('.loading') || APPS_CONTAINER.children.length === 0) {
            renderApps(MOCK_TV_APPS);
        }
    }, 600);
});
