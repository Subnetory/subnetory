# Subnetory — Format d'import CSV pour les adresses IP

> Note Sprint 2.11 : ce document historique decrit l'import CSV initial.
> Le format commun CSV/XLSX est desormais documente dans `IMPORT_FORMAT.md`.

---

> Sprint 2.0 — Endpoint : `POST /api/v1/addresses/import/csv`

---

## Format du fichier

- Encodage : **UTF-8**
- Séparateur : **virgule** (`,`)
- Première ligne : **header obligatoire** avec les noms de colonnes
- Les valeurs contenant une virgule doivent être entourées de guillemets doubles

---

## Colonnes

| Colonne | Obligatoire | Défaut | Description |
|---|---|---|---|
| `address` | ✅ | — | Adresse IPv4, ex: `192.168.1.10` |
| `subnet_id` | ✅ ou `subnet_network` | — | ID numérique du subnet en base |
| `subnet_network` | ✅ ou `subnet_id` | — | Réseau CIDR, ex: `192.168.1.0/24` |
| `mac` | — | null | Adresse MAC, format `aa:bb:cc:dd:ee:ff` |
| `hostname` | — | null | Nom d'hôte, max 100 caractères |
| `description` | — | null | Description, max 500 caractères |
| `temporary` | — | `false` | `true` / `false` — IP temporaire |
| `discovery_source` | — | `csv` | Source : `manual`, `api`, `csv`, `nmap`, `arp-scan`, `dns` |

### Règles `subnet_id` / `subnet_network`

- **Si `subnet_id` est fourni** : utilisé directement. Le subnet doit exister en base.
- **Si seulement `subnet_network`** : Subnetory cherche le subnet correspondant. Si plusieurs subnets ont le même réseau (cas multi-VRF), la ligne est rejetée avec un message indiquant les IDs ambigus.
- **Si les deux sont fournis** : `subnet_id` est prioritaire. Les deux doivent pointer vers le même subnet, sinon erreur.

---

## Exemple complet

```csv
address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source
192.168.1.10,3,,aa:bb:cc:dd:ee:ff,srv-web-01,Serveur web principal,false,csv
192.168.1.20,3,,,,Imprimante RDC,,
192.168.1.30,,192.168.1.0/24,,pc-compta-01,Poste comptabilité,false,manual
192.168.1.40,3,,00:11:22:33:44:55,laptop-tmp-01,Poste temporaire,true,
```

### Colonnes minimales

```csv
address,subnet_id
192.168.1.50,3
192.168.1.51,3
```

---

## Comportement selon l'état de l'IP

| État | Comportement par défaut | Avec `?override=true` |
|---|---|---|
| IP absente | Création complète | Création complète |
| IP existante | Mise à jour de `last_seen_at` uniquement | Mise à jour des champs fournis |
| `discovery_source` | Défini à la création, jamais modifié | Jamais modifié |

---

## Format de la réponse

```json
{
  "totalRows": 50,
  "created": 30,
  "updatedLastSeen": 18,
  "skipped": 0,
  "errors": 2,
  "errorDetails": [
    {
      "row": 5,
      "address": "999.999.999.999",
      "reason": "Invalid IP address format"
    },
    {
      "row": 12,
      "address": "192.168.1.100",
      "reason": "subnet_network '10.0.0.0/8' matches 3 subnets (ids: 1, 4, 7). Use subnet_id to disambiguate."
    }
  ]
}
```

- `totalRows` — nombre total de lignes de données (hors header, hors lignes vides)
- `created` — nouvelles entrées créées
- `updatedLastSeen` — IP existantes dont seul `last_seen_at` a été mis à jour
- `errors` — nombre de lignes en erreur
- `errorDetails[].row` — numéro de la ligne en erreur (1 = première ligne de données)

---

## Utilisation via PowerShell

```powershell
$token = (Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/auth/token `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"username":"admin","password":"admin"}').accessToken

# Import standard
$form = @{ file = Get-Item .\adresses.csv }
Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/addresses/import/csv `
    -Method POST `
    -Headers @{ Authorization = "Bearer $token" } `
    -Form $form

# Import avec override
Invoke-RestMethod `
    -Uri "http://localhost:8080/api/v1/addresses/import/csv?override=true" `
    -Method POST `
    -Headers @{ Authorization = "Bearer $token" } `
    -Form $form
```

## Utilisation via curl

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .accessToken)

curl -X POST http://localhost:8080/api/v1/addresses/import/csv \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@adresses.csv"
```

---

*Documentation Sprint 2.0 — Import CSV adresses*
