# Guide de style UI — Subnetory

> Convention obligatoire depuis l'audit du 31/07/2026 (Phase 7). Avant cet
> audit, deux systèmes de formulaire coexistaient dans le CSS (un "moderne"
> et un "legacy"), ce qui rendait certaines pages visuellement incohérentes
> entre elles (bordures, rayons, espacement différents pour le même type de
> champ). Le legacy a été retiré de tous les templates. Ce document fixe la
> règle pour toute nouvelle page ou modification : **ne jamais réintroduire
> le legacy, toujours utiliser le pattern ci-dessous.**

## Référence visuelle

Page de référence pour tout nouveau formulaire ou tout écran affichant des
informations en lecture seule : `network/address-form.html`
(`/network/addresses/{id}/edit`).

## Carte de formulaire

```html
<div class="sn-card sn-card--padded sn-form-card">
    <div class="sn-card__header">
        <h2 class="sn-card__title">Titre</h2>
    </div>

    <form class="sn-form" ...>
        ...
    </form>
</div>
```

- `sn-card--padded` : gère le padding (22px) et l'espacement entre l'en-tête
  et le contenu (`.sn-card__header` utilise une marge négative pour occuper
  toute la largeur avec sa propre bordure). Ne jamais ajouter de
  `style="padding:..."` improvisé sur le formulaire à la place — ça
  désaligne le contenu par rapport à l'en-tête de carte (bug corrigé sur
  `admin/backup-restore-confirm.html`, cause exacte : le formulaire avait
  22px de padding pendant que `.sn-card__header` en gardait 20px).
- `sn-form-card` : plafonne la largeur à 640px, adapté à un formulaire à une
  colonne. À omettre si la carte doit rester large (ex. tableau, page de
  scan) ou si elle est déjà dans une grille dédiée (voir ci-dessous).
- Alternative pour une grille de plusieurs cartes côte à côte (pages
  admin/utilisateurs, LDAP, sauvegardes) : wrapper `<div class="sn-admin-grid">`
  autour des `.sn-card` — le padding (1.5rem) est alors géré par
  `.sn-admin-grid > .sn-card`, pas besoin de `sn-card--padded` en plus.

## Champ de formulaire

```html
<div class="sn-form__group">
    <label for="champ" class="sn-form__label">Libellé</label>
    <input id="champ" class="sn-input" ...>
    <span class="sn-form__error" th:if="...">...</span>
</div>
```

Boutons de soumission : `<div class="sn-form__actions">...</div>`.

Case à cocher isolée — chip qui se surligne à la sélection (remplace
l'ancien `sn-form-label--checkbox`/`sn-form-checkbox`, retirés de tous les
templates le 01/08/2026) :

```html
<div class="sn-form__group sn-form__group--checkbox">
    <label class="sn-chip-checkbox">
        <input type="checkbox" class="sn-chip-checkbox__input" ...>
        <span class="sn-chip-checkbox__pill">Libellé</span>
    </label>
</div>
```

L'input reste un `<input type="checkbox">` réel (visuellement caché, jamais
`display:none`) : sémantique native, focus clavier et lecteurs d'écran
fonctionnent normalement. `.sn-chip-checkbox__pill` est le seul élément
visible, stylé via `input:checked ~ .sn-chip-checkbox__pill` — toujours
placer l'input avant le `<span>`, jamais l'inverse. Combinateur `~` (frère
général) et non `+` (frère immédiat) : sur un input lié par `th:field`,
Thymeleaf injecte un `<input type="hidden" name="_xxx">` juste après lui
(compagnon technique pour le binding Spring des cases décochées), ce qui
casse l'adjacence stricte attendue par `+` — bug corrigé le 01/08/2026
("IP temporaire" ne se surlignait plus au clic).

Liste à sélection multiple (rôles, contextes...) : wrapper `.sn-roles-list`
(flex-wrap, plusieurs chips par ligne) autour de plusieurs `.sn-chip-checkbox`
— voir `admin/user-form.html` (rôles, contextes).

Case à cocher compacte dans un tableau, sans libellé à côté (ex.
sélection de lignes) : classe `.sn-table-checkbox` directement sur l'input,
pas de wrapper chip (voir `network/address-reserve.html`).

**Classes interdites** (retirées de tous les templates, ne pas réintroduire) :
`sn-form-group`, `sn-form-control`, `sn-form-actions` (utiliser
`sn-form__group`, `sn-input`, `sn-form__actions`), ainsi que
`sn-form-label--checkbox` et `sn-form-checkbox` (utiliser `sn-chip-checkbox`
ci-dessus).

## Valeur en lecture seule (résumé, détail)

Pour afficher une valeur non éditable (ex. empreinte SHA-256, taille de
fichier, date) avec le même rendu visuel qu'un champ de formulaire, réutiliser
`sn-input` sur un `<div>` plutôt qu'un `<input readonly>` — un `div` respecte
le retour à la ligne naturel du texte long (`.sn-input` n'a ni
`text-overflow: ellipsis` ni `white-space: nowrap`), un `<input>` non :

```html
<div class="sn-form__group">
    <label class="sn-form__label">Empreinte SHA-256</label>
    <div class="sn-input sn-text-mono" style="height:auto;word-break:break-all;">
        f66c24f1de868eeff69fa4c74371ac8e0b26c2dc30262c6e2f659c5de92bae2b
    </div>
</div>
```

Pour une vue "détail" à plusieurs champs sans les cadrer comme des inputs
(ex. `network/address-detail.html`), utiliser plutôt `.sn-detail` /
`.sn-detail__field` / `.sn-detail__label` / `.sn-detail__value`.

## Valeur copiable au clic

Ajouter `sn-copyable` et `data-copy-value="..."` sur l'élément affichant la
valeur (voir `app.js`, comportement `[data-copy-value]`) :

```html
<div class="sn-input sn-text-mono sn-copyable" th:attr="data-copy-value=${valeur}" th:text="${valeur}"></div>
```

## Boutons désactivés

`.sn-btn:disabled` passe en gris neutre (`--color-surface-alt` /
`--color-text-muted`) quelle que soit la variante (`primary`, `danger`,
`ghost`...). Ne pas compter sur la seule réduction d'opacité pour signaler
un état désactivé : sur une variante déjà claire (`sn-btn--danger`), une
simple opacité rendait le bouton illisible et donnait l'impression d'un
rendu cassé plutôt que d'un état "pas encore actionnable". Accompagner tout
bouton désactivé par une confirmation de saisie (`data-type-to-confirm`)
d'un texte d'aide explicite sous le champ (`class="sn-form-hint"`)
expliquant quand il s'active.
