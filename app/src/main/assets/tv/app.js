// Appteka TV - Interface logic

const APPS_CONTAINER = document.getElementById('apps-container');
const STATUS = document.getElementById('status');

// Placeholder data (will be replaced by real API via native bridge)
const MOCK_TV_APPS = [
    { id: 1, name: "IPTV Pro", icon: "", downloads: "12k", size: "8.2 MB" },
    { id: 2, name: "TiviMate", icon: "", downloads: "45k", size: "15 MB" },
    { id: 3, name: "VLC", icon: "", downloads: "120k", size: "32 MB" },
    { id: 4, name: "Kodi", icon: "", downloads: "89k", size: "48 MB" },
    { id: 5, name: "SmartTube", icon: "", downloads: "67k", size: "22 MB" },
    { id: 6, name: "MX Player", icon: "", downloads: "210k", size: "18 MB" },
    { id: 7, name: "Nova Launcher TV", icon: "", downloads: "9k", size: "6 MB" },
    { id: 8, name: "TV Bro", icon: "", downloads: "31k", size: "11 MB" }
];

function createAppCard(app) {
    const card = document.createElement('div');
    card.className = 'app-card';
    card.tabIndex = 0;
    card.dataset.id = app.id;

    card.innerHTML = `
        <div class="app-icon" style="display:flex;align-items:center;justify-content:center;font-size:42px;background:#1e293b;">
            📺
        </div>
        <div class="app-info">
            <div class="app-name">${app.name}</div>
            <div class="app-meta">${app.downloads} · ${app.size}</div>
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

function onAppSelected(app) {
    STATUS.textContent = `Sélectionné : ${app.name}`;

    // Call native bridge if available
    if (window.Android && typeof window.Android.openAppDetails === 'function') {
        window.Android.openAppDetails(app.id);
    } else {
        console.log('Native bridge not available. App ID:', app.id);
    }
}

function renderApps(apps) {
    APPS_CONTAINER.innerHTML = '';

    if (!apps || apps.length === 0) {
        APPS_CONTAINER.innerHTML = '<div class="loading">Aucune application TV trouvée</div>';
        return;
    }

    apps.forEach(app => {
        APPS_CONTAINER.appendChild(createAppCard(app));
    });

    // Focus first card
    const firstCard = APPS_CONTAINER.querySelector('.app-card');
    if (firstCard) {
        firstCard.focus();
    }

    STATUS.textContent = `${apps.length} applications TV`;
}

// Focus management helpers for D-pad
function setupFocusManagement() {
    document.addEventListener('keydown', (e) => {
        const focused = document.activeElement;

        if (!focused) return;

        // Simple left/right navigation inside the row
        if (focused.classList.contains('app-card')) {
            if (e.key === 'ArrowRight') {
                const next = focused.nextElementSibling;
                if (next) next.focus();
            } else if (e.key === 'ArrowLeft') {
                const prev = focused.previousElementSibling;
                if (prev) prev.focus();
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

    // Temporary: show mock data until native bridge is connected
    setTimeout(() => {
        renderApps(MOCK_TV_APPS);
    }, 400);
});
