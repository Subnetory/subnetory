# Subnetory - Format import adresses IP

> Sprint 2.11 - Formats supportes : CSV et XLSX.

---

## Endpoints API

```text
POST /api/v1/addresses/import
POST /api/v1/addresses/import/csv
POST /api/v1/addresses/import/xlsx
```

`POST /api/v1/addresses/import` est l'endpoint recommande : l'application detecte le format a partir de l'extension `.csv` ou `.xlsx`.
Le parametre `contextId` est obligatoire sur cet endpoint pour associer l'import au bon client ou environnement.

Les endpoints `/import/csv` et `/import/xlsx` restent disponibles pour les automatisations existantes. Ils acceptent aussi `contextId`.

## Endpoints Web session

```text
POST /network/addresses/import
POST /network/addresses/import/csv
POST /network/addresses/import/xlsx
GET  /network/addresses/import-result
```

---

## Objectif

Ce document decrit le format commun utilise pour importer des adresses IP dans Subnetory.

Depuis le Sprint 2.11, les imports CSV et XLSX utilisent les memes colonnes, les memes regles metier et le meme rapport d'import.

---

## Formats supportes

| Format | Extension | Source par defaut |
|---|---|---|
| CSV | `.csv` | `csv` |
| Excel | `.xlsx` | `xlsx` |

Le format XLSX utilise la premiere feuille du classeur.

---

## Colonnes

| Colonne | Obligatoire | Description |
|---|---|---|
| `address` | Oui | Adresse IPv4, exemple `192.168.1.10` |
| `subnet_id` | `subnet_id` ou `subnet_network` | ID numerique du sous-reseau |
| `subnet_network` | `subnet_id` ou `subnet_network` | Reseau CIDR, exemple `192.168.1.0/24` |
| `mac` | Non | Adresse MAC |
| `hostname` | Non | Nom d'hote |
| `description` | Non | Description libre |
| `temporary` | Non | `true` ou `false`, defaut `false` |
| `discovery_source` | Non | Source de decouverte |

Sources autorisees :

```text
manual
api
csv
xlsx
nmap
arp-scan
dns
```

---

## Regles subnet_id / subnet_network

Si `subnet_id` est fourni, Subnetory utilise directement cet ID. Le sous-reseau doit exister.

Si seul `subnet_network` est fourni, Subnetory cherche le sous-reseau correspondant. Si plusieurs sous-reseaux ont le meme CIDR, la ligne est rejetee avec une erreur d'ambiguite.

Si les deux sont fournis, `subnet_id` est prioritaire. Les deux valeurs doivent rester coherentes.

---

## Exemple CSV

```csv
address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source
192.168.1.10,3,,aa:bb:cc:dd:ee:ff,srv-web-01,Serveur web principal,false,csv
192.168.1.20,3,,,,Imprimante RDC,,
192.168.1.30,,192.168.1.0/24,,pc-compta-01,Poste utilisateur,false,manual
```

---

## Exemple XLSX

Le fichier XLSX doit contenir les memes colonnes que le CSV.

Premiere ligne recommandee :

```text
address | subnet_id | subnet_network | mac | hostname | description | temporary | discovery_source
```

Subnetory normalise les types Excel courants :

| Type Excel | Comportement |
|---|---|
| Texte | Lu comme texte |
| Numerique | Converti en entier si possible, par exemple `42.0` devient `42` |
| Booleen | Converti en `true` ou `false` |
| Formule | Utilise la valeur calculee en cache |

---

## Comportement import

| Etat | Comportement par defaut | Avec `override=true` |
|---|---|---|
| IP absente | Creation complete | Creation complete |
| IP existante | Mise a jour de `last_seen_at` uniquement | Mise a jour des champs fournis |
| `discovery_source` | Defini a la creation | Non modifie sur une IP existante |

---

## Rapport import

Le rapport contient :

| Champ | Description |
|---|---|
| `totalRows` | Nombre de lignes traitees |
| `created` | Nombre d'adresses creees |
| `updatedLastSeen` | Nombre d'adresses existantes mises a jour |
| `skipped` | Nombre de lignes ignorees |
| `errors` | Nombre d'erreurs |
| `errorDetails` | Detail des erreurs ligne par ligne |

---

## Interface Web

Depuis la GUI :

```text
Network > Adresses IP > Importer des adresses
```

Boutons disponibles :

```text
Importer CSV
Importer Excel
```

Apres import, Subnetory affiche une page de rapport avec les compteurs et les erreurs ligne par ligne.

---

## Compatibilite

`CSV_IMPORT_FORMAT.md` reste conserve pour l'historique du Sprint 2.0 et les references existantes.

Le format commun CSV/XLSX est desormais documente dans ce fichier.
