// Appteka TV - Interface logic

const APPS_CONTAINER = document.getElementById('apps-container');
const STATUS = document.getElementById('status');

// Placeholder data (shown only if API fails)
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
        </div>
    `;

    card.addEventListener('click', () => onAppSelected(app));
    card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onAppSelected(app);
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
    STATUS.textContent = `Sélectionné : ${app.name}`;

    if (window.Android && typeof window.Android.openAppDetails === 'function') {
        window.Android.openAppDetails(String(app.id));
    } else {
        console.log('Native bridge not available. App ID:', app.id);
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
    if (firstCard) {
        firstCard.focus();
    }

    STATUS.textContent = `${apps.length} applications TV`;
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

// Called by native side when data is ready
window.loadTvApps = function(appsJson) {
    try {
        const apps = typeof appsJson === 'string' ? JSON.parse(appsJson) : appsJson;
        renderApps(apps);
    } catch (e) {
        console.error('Failed to parse apps', e);
        STATUS.textContent = 'Erreur de chargement';
    }
};

// Init
document.addEventListener('DOMContentLoaded', () => {
    setupFocusManagement();

    // Show mock briefly, then native will replace with real data
    setTimeout(() => {
        if (APPS_CONTAINER.querySelector('.loading') || APPS_CONTAINER.children.length === 0) {
            renderApps(MOCK_TV_APPS);
        }
    }, 600);
});
