"""
Converts a spreadsheet (one row per series) into a BDA backup file to import in
"Ajouter" mode, plus a CSV of the rows to check by hand.

usage: python -I -X utf8 outils/convertir_excel.py <excel.xlsx> <sortie.json> <a-verifier.csv>
"""
import csv, json, re, sys, time
import openpyxl

QUALIFICATIFS = {'tl', 'tt'}  # tirage limité / de tête: a kind of copy, not another album


def nombres(texte):
    """Tome numbers and special volumes in free text: 'n°1 à 10, n°12 + HS2'."""
    if texte is None or isinstance(texte, (int, float)):
        return [], []
    t = str(texte).lower().replace('\n', ' ')
    t = re.sub(r'n[°o)]\s*', ' ', t)
    t = re.sub(r'(\d)\s*(?:à|au|a|-)\s*(?:n\s*)?(\d)', r'\1~\2', t)
    speciaux = []
    for m in re.finditer(r'\b(hs\s*\d*|hors[- ]s[ée]rie|int[ée]grale?|coffret|compil\w*|pub\w*|best of|tl|tt)\b', t):
        mot = m.group(1).replace(' ', '')
        if mot in QUALIFICATIFS:
            continue
        if mot.startswith('hs') or mot.startswith('hors'):
            speciaux.append('HS' + re.sub(r'\D', '', mot))
        elif mot.startswith('int'):
            speciaux.append('INT')
        elif mot.startswith('compil'):
            speciaux.append('Compil')
        elif mot.startswith('pub'):
            speciaux.append('Pub')
        else:
            speciaux.append(mot.capitalize())
    tomes = []
    for a, b in re.findall(r'(\d+)\s*~\s*(\d+)', t):
        a, b = int(a), int(b)
        if a <= b <= a + 150:
            tomes += range(a, b + 1)
    reste = re.sub(r'\d+\s*~\s*\d+', ' ', t)
    reste = re.sub(r'hs\s*\d+', ' ', reste)
    tomes += [int(x) for x in re.findall(r'(?<![\d.])(\d{1,3})(?![\d.])', reste)]
    # "intégrale comprenant les n°1 à 7" is one book, not seven
    if any(s in ('INT', 'Coffret', 'Compil') for s in speciaux) and 'compren' in t:
        tomes = []
    return sorted(set(tomes)), speciaux


def entier(x):
    try:
        return int(float(x))
    except (TypeError, ValueError):
        return None


def nom_serie(brut):
    """'aigles de rome (les)' -> 'Les aigles de rome'; 'AKIRA' -> 'Akira'."""
    s = re.sub(r'\s+', ' ', str(brut)).strip()
    m = re.match(r"(.*?)\s*\((les|le|la|l')\)$", s, re.I)
    if m:
        article = m.group(2).capitalize()
        s = f"{article}{'' if article.endswith(chr(39)) else ' '}{m.group(1)}"
    if s.isupper() or s.islower():
        s = s[:1].upper() + s[1:].lower()
    return s


def cotes(cellule, un_seul):
    """{tome: (min, max, texte)} from 'n°1: 10 à 15 €\\nn°2: …'. A one-shot's plain 'de 25 à 30 €' goes to None."""
    if cellule is None or isinstance(cellule, (int, float)):
        return {}
    resultat = {}
    for ligne in re.split(r'\n|,(?=\s*n\s*[°o)+]?\s*\d)', str(cellule)):
        ligne = ligne.strip()
        if not ligne or 'non cot' in ligne.lower():
            continue
        m = re.match(r'(?:n\s*[°o)+]?\s*(\d+)|(hs\s*\d*))\s*[:;!.\-]?\s*(.*)', ligne, re.I)
        if m:
            cle = m.group(1) or 'HS' + re.sub(r'\D', '', m.group(2))
            texte = m.group(3)
        elif un_seul:
            cle, texte = None, ligne
        else:
            continue
        euros = [int(x) for x in re.findall(r'\d+', texte)]
        if not euros:
            continue
        mini, maxi = euros[0], euros[1] if len(euros) > 1 else euros[0]
        if maxi < mini:
            maxi = mini
        resultat[cle] = (mini, maxi, f"{texte.strip()} (liste)")
    return resultat


def dedicaces(cellule, un_seul):
    """Tomes marked 'dédicacé(e)' in the cote column: 'n°1 dédicacé', '1 et 2 dédicacée', '/ - dédicacé'."""
    if cellule is None or isinstance(cellule, (int, float)):
        return set()
    resultat = set()
    for ligne in re.split(r'\n|,(?=\s*n\s*[°o)+]?\s*\d)', str(cellule)):
        if 'dedicac' not in ligne.lower().replace('é', 'e'):
            continue
        m = re.match(r'\s*n\s*[°o)+]?\s*(\d+)', ligne, re.I)
        if m:
            resultat.add(m.group(1))
        elif re.match(r'\s*\d', ligne):  # "1 et 2 dédicacée"
            resultat.update(re.findall(r'\d+', ligne.split('d')[0]))
        elif un_seul:
            resultat.add(None)
    return resultat


def main(xlsx, sortie, csv_verif):
    wb = openpyxl.load_workbook(xlsx, read_only=True, data_only=True)
    lignes = [r[:10] for r in wb.worksheets[0].iter_rows(values_only=True) if any(c is not None for c in r[:10])][1:]
    maintenant = int(time.time() * 1000)
    albums, auteurs, liens, historique, a_verifier = [], {}, [], [], []
    for titre, cat, dessin, scenar, etat, possession, cote, possedes, total, manquants in lignes:
        if titre is None and dessin is None and scenar is None or str(titre).strip().upper() == 'TOTAL':
            continue
        n_pos, n_tot = entier(possedes), entier(total)
        un_shot = 'one' in str(etat or '').lower()
        tomes, speciaux = nombres(possession)
        if not tomes and not speciaux:
            if un_shot or n_tot == 1:
                tomes = [None]
            elif n_pos and n_tot and n_pos == n_tot:
                tomes = list(range(1, n_tot + 1))
            elif manquants and n_tot:
                manque, _ = nombres(manquants)
                tomes = [t for t in range(1, n_tot + 1) if t not in manque]
        serie = nom_serie(titre)
        sans_titre = not serie
        if sans_titre:
            serie = 'Sans titre (' + ', '.join(str(x).strip() for x in (scenar, dessin) if x) + ')'
        # "asterix ( hors serie)": its numbers are hors-série numbers
        hors_serie = re.search(r'\(\s*hors[- ]?s[ée]rie\s*\)', serie, re.I)
        if hors_serie:
            serie = serie[:hors_serie.start()].strip()
        volumes = [('HS' + str(t) if hors_serie and t is not None else (None if t is None else str(t))) for t in tomes] + speciaux
        importes = len(volumes)
        if sans_titre or n_pos is not None and importes != n_pos or importes == 0:
            a_verifier.append([serie, str(possession or '').replace('\n', ' '), n_pos, n_tot, importes,
                               ', '.join(v or 'one shot' for v in volumes),
                               'non importée : aucun numéro à lire' if importes == 0
                               else 'pas de titre dans la liste' if sans_titre else 'nombre différent de « n° possédés »'])
        cotes_tomes = cotes(cote, un_seul=len(volumes) == 1)
        signes = dedicaces(cote, un_seul=len(volumes) == 1)
        noms = []
        for champ in (scenar, dessin):
            for nom in re.split(r'[/,]', str(champ or '')):
                nom = nom.strip(' -')
                if nom and nom.lower() not in (n.lower() for n in noms):
                    noms.append(nom[:1].upper() + nom[1:])
        for volume in volumes:
            album_id = len(albums) + 1
            c = cotes_tomes.get(volume) if volume is not None or len(volumes) == 1 else None
            if c is None and len(volumes) == 1:
                c = cotes_tomes.get(None)
            album = dict(id=album_id, titre=serie, serie=serie, tome=volume, coteStatut='NON_CHERCHEE', ajouteLe=maintenant,
                         dedicace=volume in signes or (len(volumes) == 1 and None in signes))
            if c:
                album.update(coteMin=c[0], coteMax=c[1], coteTexte=c[2], coteStatut='EXCEL', coteMajLe=maintenant)
                historique.append(dict(id=len(historique) + 1, albumId=album_id, min=c[0], max=c[1], date=maintenant))
            albums.append(album)
            for nom in noms:
                cle = nom.lower()
                if cle not in auteurs:
                    auteurs[cle] = dict(id=len(auteurs) + 1, nom=nom, nomNorm=cle)
                liens.append(dict(albumId=album_id, auteurId=auteurs[cle]['id']))
    contenu = dict(version=1, exporteLe=maintenant, meubles=[], etageres=[], albums=albums,
                   auteurs=list(auteurs.values()), albumsAuteurs=liens, historique=historique)
    with open(sortie, 'w', encoding='utf-8') as f:
        json.dump(contenu, f, ensure_ascii=False)
    with open(csv_verif, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f, delimiter=';')
        w.writerow(['Série', 'N° possession (Excel)', 'Possédés (Excel)', 'Total (Excel)', 'Importés', 'Tomes importés', 'Remarque'])
        w.writerows(a_verifier)
    print(f"{len(albums)} albums, {sum(1 for a in albums if a['coteStatut'] == 'EXCEL')} avec cote de la liste, "
          f"{sum(1 for a in albums if a['dedicace'])} dédicacés, "
          f"{len(auteurs)} auteurs, {len(a_verifier)} séries à vérifier")


if __name__ == '__main__':
    main(*sys.argv[1:4])
