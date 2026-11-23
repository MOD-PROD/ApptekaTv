// Appteka TV — navigation spatiale commune (fiche, réglages, store d'un auteur)
// Choisit l'élément focusable le plus proche dans la direction demandée.

window.TvNav = (function () {
    function focusables(root) {
        return Array.from(root.querySelectorAll('.focusable, .app-card'))
            .filter(el => el.getClientRects().length > 0 && !el.disabled && !el.closest('[hidden], [aria-hidden="true"]'));
    }

    function move(root, dir) {
        const cur = document.activeElement;
        const list = focusables(root);
        if (!list.length) return false;
        if (!list.includes(cur)) { list[0].focus({ preventScroll: true }); list[0].scrollIntoView({ block: 'nearest' }); return true; }
        // Dans les réglages, les sections sont empilées verticalement.
        // La navigation spatiale pouvait sauter « Rechercher une mise à jour »
        // pour rejoindre directement « Plus de fonctions ».
        // Priorité stricte à l'ordre des contrôles pour Haut/Bas.
        if (root.id === 'settings-panel' && (dir === 'down' || dir === 'up')) {
            const index = list.indexOf(cur);
            const next = list[index + (dir === 'down' ? 1 : -1)];
            if (!next) return false;
            next.focus({ preventScroll: true });
            next.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
            return true;
        }
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
            return true;
        }
        // À l'extrémité d'une rangée, permettre de rejoindre les contrôles
        // suivants/précédents dans l'ordre DOM, notamment la mise à jour.
        if (dir === 'down' || dir === 'up') {
            const index = list.indexOf(cur);
            const next = list[index + (dir === 'down' ? 1 : -1)];
            if (next) {
                next.focus({ preventScroll: true });
                next.scrollIntoView({ behavior: 'smooth', block: 'center' });
                return true;
            }
        }
        return false;
    }

    // Dans un champ texte, ◀ ▶ déplacent le curseur tant qu'on n'est pas au bord
    function inputKeepsKey(e) {
        const el = document.activeElement;
        if (!el || el.tagName !== 'INPUT') return false;
        if (e.key === 'ArrowLeft') return el.selectionStart > 0;
        if (e.key === 'ArrowRight') return el.selectionEnd < el.value.length;
        return false;
    }

    const DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

    return { focusables, move, inputKeepsKey, DIRS };
})();
