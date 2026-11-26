// Intro visuelle. Les éléments interactifs restent dans leur état d'origine.
// force = true : lancé à la main.
// options.stay (Gonzo 26/11, clic sur le logo) : les éléments tombent et RESTENT en bas ;
// options.onLanded(layer, pieces) est appelé une fois tout posé. Renvoie { close() }.
// ATV (vraie TV, 24/11) : sur la box le splash ne se lançait pas. Le réglage Android
// « Supprimer les animations » (ou une échelle d'animation à 0, courante sur les box)
// donne prefers-reduced-motion: reduce dans la WebView, et l'intro était sautée.
// Sur la TV, l'intro joue quand même (3 s, n'importe quelle touche la coupe).
window.playGravitySplash = function (force, options) {
    const stay = !!(options && options.stay);
    if (!Element.prototype.animate) { console.log('[gravity] Web Animations indisponible'); return; }
    const tv = !document.documentElement.classList.contains('mobile');
    if (!force && !tv && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (document.querySelector('.gravity-splash')) return;

    const screen = document.querySelector('.safe-area');
    const items = Array.from(screen.querySelectorAll('.logo, .hbtn, .search-box, .row-icon, .app-card'))
        .map(element => ({ element, rect: element.getBoundingClientRect() }))
        .filter(({ element, rect }) => {
            const bounds = element.closest('.apps-row')?.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && rect.top >= 0 &&
                rect.bottom <= innerHeight && rect.left >= (bounds?.left ?? 0) - 1 &&
                rect.right <= Math.min(bounds?.right ?? innerWidth, innerWidth) + 1;
        });
    if (!items.length) { console.log('[gravity] aucun élément visible'); return; }
    console.log('[gravity] ' + items.length + ' éléments');

    const layer = document.createElement('div');
    layer.className = 'gravity-splash';
    layer.setAttribute('aria-hidden', 'true');
    layer.inert = true;
    const animations = items.map(({ element, rect }, i) => {
        const piece = element.cloneNode(true);
        piece.removeAttribute('id');
        piece.querySelectorAll('[id]').forEach(child => child.removeAttribute('id'));
        piece.classList.add('gravity-piece');
        Object.assign(piece.style, {
            left: rect.left + 'px',
            top: rect.top + 'px',
            width: rect.width + 'px',
            height: rect.height + 'px',
        });
        if (document.activeElement === element) piece.classList.add('gravity-focused');
        layer.appendChild(piece);

        const direction = i % 2 === 0 ? 1 : -1;
        const drift = direction * (12 + i % 4 * 9);
        const turn = direction * (10 + i % 5 * 8);
        const floor = Math.max(0, innerHeight - rect.bottom - 20 - i % 4 * 16);
        const bounce = Math.min(38, floor * 0.14);
        const pose = (x, y, angle) => `translate(${x}px, ${y}px) rotate(${angle}deg)`;
        return { piece, frames: [
            { transform: pose(0, 0, 0), easing: 'cubic-bezier(.55,.05,.9,.55)' },
            { transform: pose(drift, floor, turn), offset: 0.62, easing: 'ease-out' },
            { transform: pose(drift * 1.1, floor - bounce, turn * 0.8), offset: 0.8, easing: 'ease-in' },
            { transform: pose(drift * 1.2, floor, turn * 1.1) },
        ], delay: i % 7 * 60 };
    });

    let timer;
    let closing = false;
    function finish() {
        clearTimeout(timer);
        screen.classList.remove('gravity-running');
        layer.remove();
        window.removeEventListener('keydown', skip, true);
    }
    function skip(event) {
        event.preventDefault();
        event.stopImmediatePropagation();
        finish();
    }

    document.body.appendChild(layer);
    screen.classList.add('gravity-running');
    for (const { piece, frames, delay } of animations) piece.animate(frames, { duration: 2300, delay, fill: 'both' });
    if (stay) {
        // setTimeout plutôt que Animation.finished (absent des vieilles WebView de box).
        const end = Math.max(...animations.map(a => a.delay)) + 2300 + 60;
        setTimeout(() => {
            if (layer.isConnected && !closing && options.onLanded) options.onLanded(layer, animations.map(a => a.piece));
        }, end);
        return {
            close() {
                if (closing) return;
                closing = true;
                layer.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 250, fill: 'forwards' });
                setTimeout(finish, 260);
            },
        };
    }
    window.addEventListener('keydown', skip, true);
    timer = setTimeout(finish, 3000);   // intro de 3 s au total
    setTimeout(() => {
        if (!layer.isConnected) return;
        screen.classList.remove('gravity-running');
        layer.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 300, fill: 'forwards' });
    }, 2700);
    return { close: finish };
};
