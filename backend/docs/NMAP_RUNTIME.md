# Exécution Nmap dans l'image Docker

## Objectif

L'image d'exécution Subnetory contient Nmap afin que la fonctionnalité de scan réseau à la demande puisse utiliser la propriété `subnetory.scan.nmap-path`, dont la valeur par défaut est `nmap`.

Le paquet est installé pendant la construction de l'image. Le conteneur applicatif continue ensuite de fonctionner avec l'utilisateur non-root `subnetory`.

## Modèle de sécurité par défaut

La configuration livrée conserve les restrictions suivantes :

- utilisateur applicatif non-root ;
- aucun mode `privileged` ;
- aucun `cap_add` ;
- aucun `network_mode: host` ;
- aucune capacité fichier ajoutée au binaire Nmap ;
- réseau bridge Docker standard.

Ces restrictions sont intentionnelles. Une installation autonome doit rester sûre par défaut, même si la découverte réseau est moins complète qu'une exécution native privilégiée.

## Comportement attendu sans privilèges

Subnetory exécute actuellement Nmap avec un scan de découverte de type `nmap -sn`.

Sans privilèges de sockets brutes, Nmap peut utiliser des connexions TCP pour déterminer si une cible est joignable. Les conséquences principales sont les suivantes :

- pas de requêtes ICMP brutes garanties ;
- pas de découverte ARP du réseau local depuis un réseau bridge Docker ;
- adresses MAC généralement indisponibles hors du segment de niveau 2 visible ;
- résultat dépendant des ports TCP accessibles et des filtrages réseau ;
- résolution DNS inverse toujours dépendante de la configuration DNS du conteneur.

Le scan reste utile pour une découverte IP routée, mais il ne doit pas être présenté comme un inventaire ARP exhaustif du LAN.

## Validation de l'image

Les commandes suivantes permettent de vérifier le fonctionnement minimal non-root :

```sh
docker build -t subnetory-nmap-check .

docker run --rm --entrypoint sh subnetory-nmap-check -c '
  id
  nmap --version
  nmap -sn -n 127.0.0.1
'
```

Les résultats attendus sont :

- utilisateur `subnetory` avec un UID différent de zéro ;
- Nmap disponible dans le `PATH` ;
- scan de découverte de `127.0.0.1` terminé avec succès.

## Options d'exploitation avancées

Ces options ne sont jamais activées dans les fichiers Compose livrés.

### Réseau hôte sous Linux

Un fichier Compose d'override peut utiliser `network_mode: host` sur un hôte Linux pour rapprocher le conteneur du réseau de l'hôte.

Cette option :

- n'est pas portable de façon identique sur Docker Desktop ;
- réduit l'isolation réseau ;
- ne confère pas automatiquement les droits nécessaires aux paquets bruts ;
- doit être évaluée explicitement par l'administrateur.

### Capacité de socket brute

Une image dérivée peut ajouter une capacité fichier au binaire Nmap, par exemple avec `setcap cap_net_raw+ep`, après installation du paquet `libcap`.

Cette modification augmente les possibilités de découverte ICMP mais élargit aussi les droits du binaire. Elle doit rester une décision d'exploitation documentée, testée et revue selon la politique de sécurité locale.

### Découverte de niveau 2

Pour une collecte ARP et MAC fiable sur le LAN, la solution recommandée est un agent ou une sonde dédiée placée sur le segment réseau concerné, plutôt que l'élévation générale des privilèges du conteneur Subnetory.

## Limites de support

La livraison garantit uniquement :

- la présence de Nmap dans l'image ;
- son exécution par l'utilisateur non-root `subnetory` ;
- le fonctionnement d'un scan de découverte local sans privilèges ;
- l'absence de privilèges réseau supplémentaires dans les fichiers Compose par défaut.

La qualité de découverte d'un réseau réel dépend de la topologie, des pare-feu, du mode réseau Docker et des politiques de sécurité de l'hôte.
