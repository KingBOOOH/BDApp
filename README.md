# BDApp

Application Android personnelle pour cataloguer une collection de bandes dessinées : savoir si un album est déjà possédé, connaître sa cote BDthèque et la valeur totale de la collection.

100 % locale : pas de compte, pas de serveur, pas de télémétrie.

> **Projet personnel publié à titre de démonstration.** L'appli est faite pour un usage personnel et n'est pas distribuée : aucun APK n'est fourni. Le code est public pour montrer le travail réalisé.

## Fonctionnalités

- **Scan en rafale** des codes-barres ISBN (CameraX + ML Kit), avec la date d'ajout (aujourd'hui par défaut, modifiable ici et dans la fiche). Chaque album est enregistré tout de suite, puis la cote, la série, le tome et la couverture arrivent en arrière-plan. Les albums anciens sans code-barres s'ajoutent par leur titre.
- **Cote BDthèque** : l'album est retrouvé sur bedetheque.com par ISBN, puis l'édition est identifiée. Quand l'EO et ses rééditions partagent l'ISBN avec des cotes différentes, l'album passe en « édition à choisir ». L'écran détail montre alors chaque édition avec son dépôt légal, sa cote et la note « Info édition ».
- **Vérifier** : scanner (ou taper) un ISBN dit tout de suite si l'album est déjà dans la collection, y compris pour les albums importés sans ISBN (BDthèque donne la série et le tome, comparés à la collection). Recherche par titre, série ou auteur.
- **Liste** : recherche par titre, série ou auteur, tri, filtres (cote manquante, édition à choisir, cotées, dédicacées), regroupement par série et valeur totale estimée.
- **Détail** : historique de la cote, rafraîchissement, cote saisie à la main, lien manuel vers une fiche.
- **Mises à jour en masse** (toute la collection, une série, un auteur) via WorkManager, avec notification, annulation et reprise après interruption.
- **Sauvegarde** : export et import de toute la collection dans un fichier JSON, par exemple pour passer d'un téléphone à l'autre.
- Consultation hors ligne : les couvertures sont copiées en local.

## Stack

Kotlin, Jetpack Compose (Material 3), Room 3, Hilt, WorkManager, CameraX + ML Kit Barcode Scanning, OkHttp + Jsoup, Retrofit + kotlinx.serialization, Coil 3. Android 12 minimum (minSdk 31).

## Organisation du code

```
app/src/main/java/fr/noe/bda/
├── data/
│   ├── lookup/       OpenLibrary (source principale) et Google Books (complète les champs vides)
│   ├── bedetheque/   Scraping BDthèque : Selectors.kt (tous les sélecteurs), client, parsing, matching
│   ├── local/        Room : entités, DAO, base
│   ├── backup/       Export et import JSON
│   ├── Cotes.kt      Règles d'application d'une cote (historique, erreurs, cote manuelle)
│   └── Couvertures.kt Copie locale des couvertures et couleur des dos
├── work/             CoteWorker (file des nouveaux albums et mises à jour en masse)
├── di/               Modules Hilt
└── ui/               Écrans Compose (verifier, liste, ajout, detail, outils, emplacements, faisabilite)
```

## BDthèque : ce qu'il faut savoir

Il n'existe pas d'API publique : l'appli lit les pages HTML. Tout ce qui dépend du HTML du site est regroupé dans `data/bedetheque/Selectors.kt`. Si le site change, c'est ce fichier qu'il faut corriger, avec les extraits de pages de test dans `app/src/test/resources/bedetheque/`.

Comportements du site observés en octobre 2026 :
- La recherche (`search/albums?RechISBN=…`) exige le cookie `csrf_cookie_bel` (posé par la page d'accueil) et un en-tête `Referer`. Sans eux, elle renvoie un 200 **sans résultat**.
- Une recherche qui n'a qu'un seul résultat redirige (302) directement vers la fiche de l'album.
- Une fiche album contient toutes les éditions (`article.bdt-edition#ed-<id>`), chacune avec son ISBN et son estimation.

Politesse : une requête à la fois, au moins 2,5 s d'écart, User-Agent explicite, backoff sur les erreurs 429, 503 et les timeouts. Après un deuxième 403, l'appli arrête d'interroger le site jusqu'au prochain redémarrage. Un échec n'efface jamais une cote connue.

## Lancer le projet

1. Ouvrir le dossier dans Android Studio (JDK 21 fourni avec Android Studio).
2. Facultatif : ajouter une clé Google Books dans `local.properties` (fichier jamais commité) :
   ```
   GOOGLE_BOOKS_API_KEY=…
   ```
   Sans clé, seul OpenLibrary répond de façon fiable. BDthèque complète de toute façon la série, le tome et le titre.
3. Lancer sur un téléphone, ou sur un émulateur API 31 ou plus.

Pour scanner depuis un PC, lancer l'émulateur avec la webcam comme caméra arrière :

```
emulator -avd <nom> -camera-back webcam0
```

## Importer une liste Excel

`outils/convertir_excel.py` convertit un tableau Excel (une ligne par série) en fichier d'import pour l'appli.

Colonnes attendues : titre, catégorie, dessinateur, auteur, état de la série, n° possédés en texte libre, cote, nombre possédé, total, manquants.

```
python -I -X utf8 outils/convertir_excel.py liste.xlsx bda-import-liste.json series-a-verifier.csv
```

Le fichier JSON s'importe dans Outils › Importer › **Ajouter**, ce qui n'efface rien. Le CSV liste les séries dont le texte libre est incohérent ou illisible.

Les albums importés n'ont pas d'ISBN. La mise à jour des cotes les cherche sur BDthèque par série et numéro de tome. Une cote venue de la liste n'est remplacée que par une vraie estimation BDthèque. Scanner ensuite un album importé lui ajoute son ISBN, au lieu de créer un doublon.

## Tests

```
./gradlew testDebugUnitTest          # parsing BDthèque sur pages réelles, matching, client HTTP, lookup
./gradlew connectedDebugAndroidTest  # base Room + règles de cote, reprise, sauvegarde (émulateur ou téléphone)
```

Attention : `connectedDebugAndroidTest` désinstalle l'appli à la fin, ce qui efface ses données sur l'appareil de test.

L'écran Outils › « Test BDthèque (diagnostic) » lance toute la chaîne sur une liste d'ISBN et produit un rapport, partageable ou lisible avec `adb logcat -s BDA-Rapport`.

## Limites connues

- L'import remplace toute la collection (pas de fusion).

## Licence

Code sous licence MIT (voir `LICENSE`). Les extraits de pages de `app/src/test/resources/bedetheque/` restent la propriété de bedetheque.com et ne servent qu'aux tests.
