(function () {
  'use strict';

  // Petites aides i18n (audit du 02/08/2026) : les libellés statiques sont
  // deja localises via th:text/th:attr dans les templates ; les quelques
  // libellés que ce script génère dynamiquement (messages de progression,
  // suggestions IP, aperçu cron...) lisent leurs traductions depuis des
  // attributs data-i18n-* poses sur <body> par layout/base.html (voir
  // messages.properties / messages_fr.properties / messages_en.properties).
  // Le deuxième argument sert de repli si l'attribut est absent (pages qui
  // n'étendent pas layout/base, ou attribut pas encore déployé).
  function i18n(key, fallback) {
    var value = document.body ? document.body.getAttribute('data-i18n-' + key) : null;
    return value != null && value !== '' ? value : fallback;
  }

  // Remplacement simple de type MessageFormat : fmt('IP {0}', ['1.2.3.4'])
  // -> 'IP 1.2.3.4'. Suffisant pour les gabarits {0}/{1}/{2} utilisés ici,
  // cohérent avec la convention déjà en place côté serveur (ex. scan.error.generic).
  function fmt(template, args) {
    return template.replace(/\{(\d+)\}/g, function (match, index) {
      var value = args[Number(index)];
      return value != null ? value : match;
    });
  }

  window.snI18n = { t: i18n, fmt: fmt };

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-confirm]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        if (!window.confirm(el.getAttribute('data-confirm'))) {
          e.preventDefault();
        }
      });
    });

    // Navigation "drill-down" (audit du 31/07/2026) : une ligne de tableau
    // marquée data-href navigue vers l'URL indiquée au clic, sauf si le
    // clic vient d'un lien/bouton/champ deja interactif dans la ligne
    // (Modifier, Supprimer, cases a cocher...), pour ne pas interferer
    // avec les actions existantes.
    document.querySelectorAll('tr[data-href]').forEach(function (row) {
      row.addEventListener('click', function (e) {
        if (e.target.closest('a, button, input, select, textarea, label')) return;
        window.location.href = row.getAttribute('data-href');
      });
    });

    // Confirmation forte par saisie exacte (Phase 7 audit, 31/07/2026) :
    // le bouton de soumission reste desactive tant que le texte saisi ne
    // correspond pas exactement a la valeur attendue. Utilise pour la
    // restauration de sauvegarde (RESTORE_OPERATIONS.md : jamais un simple
    // clic), pas de logique metier cote client au-dela de l'UX — la
    // verification est toujours refaite cote serveur.
    document.querySelectorAll('[data-type-to-confirm]').forEach(function (input) {
      var expected = input.getAttribute('data-type-to-confirm');
      var submitSelector = input.getAttribute('data-type-to-confirm-submit');
      var submitButton = submitSelector ? document.querySelector(submitSelector) : null;
      if (!submitButton) return;
      var check = function () {
        submitButton.disabled = input.value !== expected;
      };
      input.addEventListener('input', check);
      check();
    });

    // Normalisation majuscules a la saisie (audit 02/08/2026, correctif
    // ELEVEE lie au format de code Site) : certains champs (ex. code Site,
    // desormais valide par un pattern majuscules/chiffres/_/- cote serveur)
    // beneficient d'une conversion automatique a la frappe plutot que de
    // laisser l'utilisateur decouvrir l'erreur de format seulement a la
    // soumission. Conserve la position du curseur pour ne pas gener la
    // frappe. Toujours revalide cote serveur (Bean Validation) : ceci est un
    // confort UX, pas un mecanisme de securite.
    document.querySelectorAll('[data-uppercase]').forEach(function (input) {
      input.addEventListener('input', function () {
        var start = input.selectionStart;
        var end = input.selectionEnd;
        input.value = input.value.toUpperCase();
        if (start !== null && end !== null) {
          input.setSelectionRange(start, end);
        }
      });
    });

    document.querySelectorAll('[data-auto-submit]').forEach(function (el) {
      el.addEventListener('change', function () {
        var form = el.closest('form');
        if (form) form.submit();
      });
    });

    document.querySelectorAll('[data-check-all], [data-uncheck-all]').forEach(function (button) {
      button.addEventListener('click', function () {
        var selector = button.getAttribute('data-check-all') || button.getAttribute('data-uncheck-all');
        var container = selector ? document.querySelector(selector) : null;
        if (!container) return;

        var checked = button.hasAttribute('data-check-all');
        container.querySelectorAll('input[type="checkbox"]:not(:disabled)').forEach(function (checkbox) {
          checkbox.checked = checked;
          checkbox.dispatchEvent(new Event('change', { bubbles: true }));
        });
      });
    });

    document.querySelectorAll('[data-file-drop]').forEach(function (drop) {
      var input = drop.querySelector('[data-file-input]');
      var fileName = drop.querySelector('[data-file-name]');
      if (!input) return;

      // Regression fix (audit du 02/08/2026) : le libellé "aucun fichier"
      // est deja localise via th:text dans le template (ex. import.filedrop.noneSelected,
      // backup.filePicker.none). On capture ce texte initial une seule fois
      // au lieu de le remplacer par une chaîne française codée en dur a
      // chaque changement — sinon le placeholder repassait systematiquement
      // en français quelle que soit la langue active dès la première
      // sélection/désélection de fichier.
      var emptyLabel = fileName ? fileName.textContent : '';

      function updateFileName() {
        var file = input.files && input.files.length > 0 ? input.files[0] : null;
        if (fileName) fileName.textContent = file ? file.name : emptyLabel;
        drop.classList.toggle('has-file', !!file);
      }

      ['dragenter', 'dragover'].forEach(function (eventName) {
        drop.addEventListener(eventName, function (event) {
          event.preventDefault();
          event.stopPropagation();
          drop.classList.add('is-dragover');
        });
      });

      ['dragleave', 'drop'].forEach(function (eventName) {
        drop.addEventListener(eventName, function (event) {
          event.preventDefault();
          event.stopPropagation();
          drop.classList.remove('is-dragover');
        });
      });

      drop.addEventListener('drop', function (event) {
        var files = event.dataTransfer && event.dataTransfer.files;
        if (!files || files.length === 0) return;
        input.files = files;
        updateFileName();
      });

      input.addEventListener('change', updateFileName);
      updateFileName();
    });

    document.querySelectorAll('.sn-profile-chip').forEach(function (chip) {
      var avatar = chip.querySelector('.sn-profile-chip__avatar');
      var name = chip.querySelector('.sn-profile-chip__text strong');
      if (!avatar || !name) return;
      var raw = (name.textContent || '').trim();
      if (!raw) return;
      var parts = raw.split(/[^a-zA-Z0-9]+/).filter(Boolean);
      var initials = parts.length > 1
        ? (parts[0][0] + parts[1][0])
        : raw.slice(0, 2);
      avatar.textContent = initials.toUpperCase();
      avatar.setAttribute('aria-label', fmt(i18n('initials-aria', 'Initiales {0}'), [initials.toUpperCase()]));
    });

    document.querySelectorAll('[data-scan-form]').forEach(function (form) {
      var button = form.querySelector('[data-scan-submit]');
      var terminal = document.querySelector('[data-scan-terminal]');
      var followup = document.querySelector('[data-scan-followup]');
      var status = document.querySelector('[data-scan-status]');
      var command = document.querySelector('.sn-command-preview code');
      var submitted = false;

      function updateCommandPreview() {
        if (!command) return;
        var cidr = command.getAttribute('data-command-preview') || '';
        var resolveDns = form.querySelector('input[name="resolveDns"]');
        var arpPing = form.querySelector('input[name="arpPing"]');
        var timing = form.querySelector('select[name="timing"]');
        var dnsServers = form.querySelector('[name="dnsServers"]');
        var argButtons = form.querySelectorAll('[data-scan-arg]');
        var parts = ['nmap', '-sn'];

        if (resolveDns && resolveDns.checked) {
          parts.push('-R');
          var normalizedDnsServers = normalizeScanDnsServers(dnsServers ? dnsServers.value : '');
          if (normalizedDnsServers.length > 0) {
            parts.push('--dns-servers', normalizedDnsServers.join(','));
          }
        } else {
          parts.push('-n');
        }

        if (arpPing && !arpPing.checked) {
          parts.push('--disable-arp-ping');
        }

        if (timing && timing.value === 'fast') {
          parts.push('-T4');
        } else if (timing && timing.value === 'gentle') {
          parts.push('-T2');
        }

        parts.push('-oX', '-', cidr);
        command.textContent = parts.join(' ');

        argButtons.forEach(function (arg) {
          var value = arg.getAttribute('data-scan-arg');
          var active = false;
          if (value === 'dns-on') active = !!(resolveDns && resolveDns.checked);
          if (value === 'dns-off') active = !!(resolveDns && !resolveDns.checked);
          if (value === 'arp-off') active = !!(arpPing && !arpPing.checked);
          if (value === 'timing-gentle') active = !!(timing && timing.value === 'gentle');
          if (value === 'timing-fast') active = !!(timing && timing.value === 'fast');
          arg.classList.toggle('is-active', active);
          arg.setAttribute('aria-pressed', active ? 'true' : 'false');
        });
      }

      form.querySelectorAll('[data-scan-option]').forEach(function (option) {
        option.addEventListener('change', updateCommandPreview);
        option.addEventListener('input', updateCommandPreview);
      });

      form.querySelectorAll('[data-scan-arg]').forEach(function (arg) {
        arg.addEventListener('click', function () {
          var value = arg.getAttribute('data-scan-arg');
          var resolveDns = form.querySelector('input[name="resolveDns"]');
          var arpPing = form.querySelector('input[name="arpPing"]');
          var timing = form.querySelector('select[name="timing"]');

          if (value === 'dns-on' && resolveDns) {
            resolveDns.checked = true;
            resolveDns.dispatchEvent(new Event('change', { bubbles: true }));
          } else if (value === 'dns-off' && resolveDns) {
            resolveDns.checked = false;
            resolveDns.dispatchEvent(new Event('change', { bubbles: true }));
          } else if (value === 'arp-off' && arpPing) {
            arpPing.checked = !arpPing.checked;
            arpPing.dispatchEvent(new Event('change', { bubbles: true }));
          } else if (value === 'timing-gentle' && timing) {
            timing.value = timing.value === 'gentle' ? 'normal' : 'gentle';
            timing.dispatchEvent(new Event('change', { bubbles: true }));
          } else if (value === 'timing-fast' && timing) {
            timing.value = timing.value === 'fast' ? 'normal' : 'fast';
            timing.dispatchEvent(new Event('change', { bubbles: true }));
          }

          updateCommandPreview();
        });
      });
      updateCommandPreview();

      form.addEventListener('submit', function (event) {
        if (submitted) {
          event.preventDefault();
          return;
        }
        submitted = true;

        if (button) {
          button.disabled = true;
          button.textContent = i18n('scan-running-button', 'Scan en cours…');
        }
        if (status) {
          status.textContent = i18n('scan-status-running', 'En cours');
          status.className = 'sn-badge sn-badge--blue';
        }
        if (terminal) {
          var commandText = command ? command.textContent.trim() : 'scan';
          terminal.innerHTML = '';
          [
            '$ ' + commandText,
            i18n('scan-starting', 'Initialisation du scan…'),
            i18n('scan-progress-hint', 'Exécution en cours, merci de patienter.')
          ].forEach(function (line) {
            var p = document.createElement('p');
            p.textContent = line;
            terminal.appendChild(p);
          });
          terminal.classList.add('is-running');
        }
        if (followup) {
          window.setTimeout(function () {
            followup.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }, 50);
        }
      });
    });

    function normalizeScanDnsServers(value) {
      if (!value) return [];
      var seen = [];
      value.trim().split(/[,;\s]+/).forEach(function (candidate) {
        if (!candidate) return;
        if (!/^(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)$/.test(candidate)) return;
        if (seen.indexOf(candidate) === -1 && seen.length < 5) {
          seen.push(candidate);
        }
      });
      return seen;
    }

    document.querySelectorAll('[data-copy-target]').forEach(function (copyButton) {
      copyButton.addEventListener('click', function () {
        var selector = copyButton.getAttribute('data-copy-target');
        var target = selector ? document.querySelector(selector) : null;
        var initialLabel = copyButton.getAttribute('data-copy-label') || copyButton.textContent;
        var doneLabel = copyButton.getAttribute('data-copy-done') || i18n('copy-done', 'Copié');
        var value = target ? target.innerText.trim() : '';
        if (!value) return;

        function markDone() {
          copyButton.textContent = doneLabel;
          window.setTimeout(function () {
            copyButton.textContent = initialLabel;
          }, 1800);
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(value).then(markDone).catch(function () {
            fallbackCopy(value, markDone);
          });
        } else {
          fallbackCopy(value, markDone);
        }
      });
    });

    // Copie au clic sur la valeur elle-même (empreintes SHA-256 des
    // sauvegardes, etc.) : contrairement à [data-copy-target] ci-dessus
    // (un bouton séparé qui copie un autre élément), ici l'élément
    // affichant la valeur est lui-même cliquable. La valeur complète
    // vient de data-copy-value, ce qui permet à l'élément de continuer
    // à afficher une version tronquée/courte tout en copiant l'intégralité.
    document.querySelectorAll('[data-copy-value]').forEach(function (el) {
      el.classList.add('sn-copyable');
      if (!el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
      if (!el.hasAttribute('role')) el.setAttribute('role', 'button');
      var baseTitle = el.getAttribute('title') || i18n('copy-hint', 'Cliquer pour copier');
      el.setAttribute('title', baseTitle);

      function doCopy() {
        var value = el.getAttribute('data-copy-value');
        if (!value) return;

        function markCopied() {
          el.classList.add('sn-copyable--copied');
          el.setAttribute('title', i18n('copy-done', 'Copié'));
          window.setTimeout(function () {
            el.classList.remove('sn-copyable--copied');
            el.setAttribute('title', baseTitle);
          }, 1400);
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(value).then(markCopied).catch(function () {
            fallbackCopy(value, markCopied);
          });
        } else {
          fallbackCopy(value, markCopied);
        }
      }

      el.addEventListener('click', doCopy);
      el.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          doCopy();
        }
      });
    });

    function fallbackCopy(value, onDone) {
      var textarea = document.createElement('textarea');
      textarea.value = value;
      textarea.setAttribute('readonly', 'readonly');
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        onDone();
      } finally {
        document.body.removeChild(textarea);
      }
    }
  });

})();


(function () {
  'use strict';

  var i18n = window.snI18n.t;
  var fmt = window.snI18n.fmt;

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-ip-suggestion]').forEach(function (container) {
      var subnetSelectId = container.getAttribute('data-subnet-select-id') || 'subnetId';
      var addressInputId = container.getAttribute('data-address-input-id') || 'address';
      var count = container.getAttribute('data-count') || '5';

      var subnetSelect = document.getElementById(subnetSelectId);
      var addressInput = document.getElementById(addressInputId);
      var button = container.querySelector('[data-ip-suggest-button]');
      var message = container.querySelector('[data-ip-suggest-message]');
      var results = container.querySelector('[data-ip-suggest-results]');

      if (!subnetSelect || !addressInput || !button || !message || !results) {
        return;
      }

      function clearResults() {
        results.innerHTML = '';
        results.hidden = true;
      }

      function setMessage(text, isError) {
        message.textContent = text || '';
        message.hidden = !text;
        message.className = isError ? 'sn-form__error' : '';
      }

      function updateButtonState() {
        var hasSubnet = !!subnetSelect.value;
        button.disabled = !hasSubnet;

        if (!hasSubnet) {
          clearResults();
          setMessage(i18n('suggest-select-subnet', 'Sélectionnez un sous-réseau pour activer la suggestion.'), false);
        } else {
          setMessage('', false);
        }
      }

      function renderSuggestions(ips) {
        clearResults();

        if (!ips || ips.length === 0) {
          setMessage(i18n('suggest-no-results', 'Aucune IP disponible dans ce sous-réseau.'), false);
          return;
        }

        ips.forEach(function (ip) {
          var ipButton = document.createElement('button');
          ipButton.type = 'button';
          ipButton.className = 'sn-btn sn-btn--ghost sn-btn--sm sn-text-mono';
          ipButton.textContent = ip;
          ipButton.addEventListener('click', function () {
            addressInput.value = ip;
            clearResults();
            setMessage(fmt(i18n('suggest-selected', 'IP sélectionnée : {0}'), [ip]), false);
            addressInput.focus();
          });
          results.appendChild(ipButton);
        });

        results.hidden = false;
        setMessage(i18n('suggest-click-hint', 'Cliquez sur une IP pour remplir automatiquement le champ adresse.'), false);
      }

      subnetSelect.addEventListener('change', updateButtonState);

      button.addEventListener('click', function () {
        if (!subnetSelect.value) {
          updateButtonState();
          return;
        }

        clearResults();
        button.disabled = true;
        setMessage(i18n('suggest-loading', 'Recherche des IPs disponibles...'), false);

        fetch('/network/subnets/' + encodeURIComponent(subnetSelect.value) + '/available-ips?count=' + encodeURIComponent(count), {
          headers: {
            'Accept': 'application/json'
          }
        })
          .then(function (response) {
            if (!response.ok) {
              throw new Error('HTTP ' + response.status);
            }
            return response.json();
          })
          .then(function (data) {
            renderSuggestions(data.availableIps || []);
          })
          .catch(function () {
            clearResults();
            setMessage(i18n('suggest-error', 'Impossible de récupérer les IPs disponibles pour ce sous-réseau.'), true);
          })
          .finally(function () {
            button.disabled = !subnetSelect.value;
          });
      });

      updateButtonState();
    });
  });

})();


(function () {
  'use strict';

  // Configurateur cron visuel (planification sauvegarde, 01/08/2026) :
  // enrichissement progressif au-dessus du champ texte existant
  // (data-cron-raw), qui reste la seule valeur soumise au serveur — la
  // validation réelle (format à 6 champs) reste faite côté serveur par
  // BackupConfigurationService. Les modes Quotidien/Hebdomadaire/Mensuel
  // génèrent l'expression à partir de contrôles simples ; toute expression
  // qu'ils ne reconnaissent pas exactement au chargement retombe en mode
  // Personnalisé, où le champ texte reste librement éditable.
  var i18n = window.snI18n.t;
  var fmt = window.snI18n.fmt;

  var DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
  var DAY_LABEL_FALLBACK = {
    MON: 'lundi', TUE: 'mardi', WED: 'mercredi', THU: 'jeudi',
    FRI: 'vendredi', SAT: 'samedi', SUN: 'dimanche'
  };
  var DAY_DATA_KEY = {
    MON: 'cron-day-mon', TUE: 'cron-day-tue', WED: 'cron-day-wed', THU: 'cron-day-thu',
    FRI: 'cron-day-fri', SAT: 'cron-day-sat', SUN: 'cron-day-sun'
  };

  function dayLabel(code) {
    return i18n(DAY_DATA_KEY[code], DAY_LABEL_FALLBACK[code]);
  }

  // Pluralisation simple (fr/en : singulier seulement pour n == 1) via deux
  // clés .one / .other, sur le même principe que les gabarits {0}/{1}/{2}
  // ci-dessus. Suffisant pour les deux langues actuellement supportées.
  function pluralKey(base, n) {
    return n === 1 ? base + '-one' : base + '-other';
  }

  function pad2(value) {
    var n = parseInt(value, 10);
    if (isNaN(n)) n = 0;
    return (n < 10 ? '0' : '') + n;
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-cron-builder]').forEach(function (builder) {
      var rawInput = builder.querySelector('[data-cron-raw]');
      var preview = builder.querySelector('[data-cron-preview]');
      var modeButtons = builder.querySelectorAll('[data-cron-mode]');
      var panels = builder.querySelectorAll('[data-cron-panel]');
      var monthlyDaySelect = builder.querySelector('[data-cron-day-of-month]');
      var intervalValueInput = builder.querySelector('[data-cron-interval-value]');
      var intervalUnitSelect = builder.querySelector('[data-cron-interval-unit]');
      var intervalTimeLabel = builder.querySelector('[data-cron-interval-time-label]');
      var intervalTimeInput = builder.querySelector('[data-cron-interval-time-input]');
      if (!rawInput) return;

      function updateIntervalUnitUi(resetDefault) {
        var isDays = intervalUnitSelect && intervalUnitSelect.value === 'days';
        if (intervalTimeLabel) intervalTimeLabel.hidden = !isDays;
        if (intervalTimeInput) intervalTimeInput.hidden = !isDays;
        if (intervalValueInput) {
          intervalValueInput.max = isDays ? '31' : '23';
          if (resetDefault) intervalValueInput.value = isDays ? '2' : '6';
        }
      }

      function panelFor(mode) {
        return builder.querySelector('[data-cron-panel="' + mode + '"]');
      }

      function timeInputFor(mode) {
        var p = panelFor(mode);
        return p ? p.querySelector('[data-cron-time]') : null;
      }

      function setTimeValue(mode, hour, minute) {
        var input = timeInputFor(mode);
        if (input) input.value = pad2(hour) + ':' + pad2(minute);
      }

      function timeValue(mode) {
        var input = timeInputFor(mode);
        var raw = input && input.value ? input.value.split(':') : ['02', '00'];
        return { hour: pad2(raw[0]), minute: pad2(raw[1] || '0') };
      }

      function selectedDays() {
        var days = [];
        builder.querySelectorAll('[data-cron-days] input:checked').forEach(function (cb) {
          days.push(cb.value);
        });
        days.sort(function (a, b) { return DAY_ORDER.indexOf(a) - DAY_ORDER.indexOf(b); });
        return days;
      }

      function currentMode() {
        var active = builder.querySelector('[data-cron-mode].is-active');
        return active ? active.getAttribute('data-cron-mode') : 'custom';
      }

      function generate(mode) {
        if (mode === 'daily') {
          var t = timeValue('daily');
          rawInput.value = '0 ' + parseInt(t.minute, 10) + ' ' + parseInt(t.hour, 10) + ' * * *';
          preview.textContent = fmt(i18n('cron-daily', 'Tous les jours à {0}:{1}.'), [t.hour, t.minute]);
        } else if (mode === 'weekly') {
          var t2 = timeValue('weekly');
          var days = selectedDays();
          if (days.length === 0) {
            // Au moins un jour est nécessaire, sinon l'expression ne se
            // déclencherait jamais : le jour courant est coché par défaut.
            var todayCode = DAY_ORDER[(new Date().getDay() + 6) % 7];
            var cb = builder.querySelector('[data-cron-days] input[value="' + todayCode + '"]');
            if (cb) cb.checked = true;
            days = [todayCode];
          }
          rawInput.value = '0 ' + parseInt(t2.minute, 10) + ' ' + parseInt(t2.hour, 10) + ' * * ' + days.join(',');
          var labels = days.map(dayLabel);
          preview.textContent = fmt(i18n('cron-weekly', 'Chaque {0} à {1}:{2}.'), [labels.join(', '), t2.hour, t2.minute]);
        } else if (mode === 'monthly') {
          var t3 = timeValue('monthly');
          var dom = monthlyDaySelect ? monthlyDaySelect.value : '1';
          rawInput.value = '0 ' + parseInt(t3.minute, 10) + ' ' + parseInt(t3.hour, 10) + ' ' + dom + ' * *';
          preview.textContent = dom === '1'
            ? fmt(i18n('cron-monthly-first', 'Le 1er de chaque mois à {0}:{1}.'), [t3.hour, t3.minute])
            : fmt(i18n('cron-monthly-nth', 'Le {0} de chaque mois à {1}:{2}.'), [dom, t3.hour, t3.minute]);
        } else if (mode === 'interval') {
          var unit = intervalUnitSelect ? intervalUnitSelect.value : 'hours';
          var n = intervalValueInput ? parseInt(intervalValueInput.value, 10) : NaN;
          if (isNaN(n) || n < 1) n = 1;

          if (unit === 'days') {
            if (n > 31) n = 31;
            if (intervalValueInput) intervalValueInput.value = n;
            var t4 = intervalTimeInput && intervalTimeInput.value
              ? { hour: pad2(intervalTimeInput.value.split(':')[0]), minute: pad2(intervalTimeInput.value.split(':')[1]) }
              : { hour: '02', minute: '00' };
            rawInput.value = '0 ' + parseInt(t4.minute, 10) + ' ' + parseInt(t4.hour, 10) + ' */' + n + ' * *';
            preview.textContent = fmt(
              i18n(pluralKey('cron-interval-days', n), 'Tous les {0} jour(s) à {1}:{2} (à partir du 1er du mois).'),
              [n, t4.hour, t4.minute]
            );
          } else {
            if (n > 23) n = 23;
            if (intervalValueInput) intervalValueInput.value = n;
            rawInput.value = '0 0 */' + n + ' * * *';
            preview.textContent = fmt(
              i18n(pluralKey('cron-interval-hours', n), 'Toutes les {0} heure(s) (à partir de minuit).'),
              [n]
            );
          }
        }
      }

      function setMode(mode) {
        modeButtons.forEach(function (btn) {
          btn.classList.toggle('is-active', btn.getAttribute('data-cron-mode') === mode);
        });
        panels.forEach(function (p) {
          p.hidden = p.getAttribute('data-cron-panel') !== mode;
        });
        rawInput.readOnly = mode !== 'custom';
        if (mode === 'custom') {
          preview.textContent = '';
        } else {
          generate(mode);
        }
      }

      modeButtons.forEach(function (btn) {
        btn.addEventListener('click', function () {
          setMode(btn.getAttribute('data-cron-mode'));
        });
      });

      builder.querySelectorAll('[data-cron-time]').forEach(function (input) {
        input.addEventListener('input', function () { generate(currentMode()); });
      });
      builder.querySelectorAll('[data-cron-days] input').forEach(function (cb) {
        cb.addEventListener('change', function () { generate(currentMode()); });
      });
      if (monthlyDaySelect) {
        monthlyDaySelect.addEventListener('change', function () { generate(currentMode()); });
      }
      if (intervalValueInput) {
        intervalValueInput.addEventListener('input', function () { generate(currentMode()); });
      }
      if (intervalUnitSelect) {
        intervalUnitSelect.addEventListener('change', function () {
          updateIntervalUnitUi(true);
          generate(currentMode());
        });
      }
      if (intervalTimeInput) {
        intervalTimeInput.addEventListener('input', function () { generate(currentMode()); });
      }
      updateIntervalUnitUi(false);

      // Ré-interprétation de l'expression existante au chargement : si elle
      // correspond exactement à un des motifs générés ci-dessus, les
      // contrôles sont pré-remplis et le bon mode est activé ; sinon repli
      // sur "Personnalisé" (le champ texte garde sa valeur telle quelle).
      function isNumInRange(value, max) {
        return /^\d{1,2}$/.test(value) && parseInt(value, 10) <= max;
      }

      (function parseExisting() {
        var fields = (rawInput.value || '').trim().split(/\s+/);
        if (fields.length !== 6) { setMode('custom'); return; }

        var sec = fields[0], min = fields[1], hour = fields[2],
            dom = fields[3], month = fields[4], dow = fields[5];

        if (sec === '0' && dom === '*' && month === '*' && dow === '*'
            && isNumInRange(min, 59) && isNumInRange(hour, 23)) {
          setTimeValue('daily', hour, min);
          setMode('daily');
          return;
        }

        if (sec === '0' && dom === '*' && month === '*'
            && isNumInRange(min, 59) && isNumInRange(hour, 23)) {
          var dowParts = dow.toUpperCase().split(',');
          var validDays = dowParts.every(function (d) { return DAY_ORDER.indexOf(d) !== -1; });
          if (validDays) {
            setTimeValue('weekly', hour, min);
            builder.querySelectorAll('[data-cron-days] input').forEach(function (cb) {
              cb.checked = dowParts.indexOf(cb.value) !== -1;
            });
            setMode('weekly');
            return;
          }
        }

        if (sec === '0' && month === '*' && dow === '*'
            && isNumInRange(min, 59) && isNumInRange(hour, 23)
            && isNumInRange(dom, 31) && parseInt(dom, 10) >= 1) {
          setTimeValue('monthly', hour, min);
          if (monthlyDaySelect) monthlyDaySelect.value = String(parseInt(dom, 10));
          setMode('monthly');
          return;
        }

        // Intervalle — "toutes les N heures" : minutes/dom/mois/jour figés,
        // seul le champ heures porte un pas (*/N).
        var hourStep = /^\*\/(\d{1,2})$/.exec(hour);
        if (sec === '0' && min === '0' && dom === '*' && month === '*' && dow === '*'
            && hourStep && parseInt(hourStep[1], 10) >= 1 && parseInt(hourStep[1], 10) <= 23) {
          if (intervalUnitSelect) intervalUnitSelect.value = 'hours';
          updateIntervalUnitUi(false);
          if (intervalValueInput) intervalValueInput.value = hourStep[1];
          setMode('interval');
          return;
        }

        // Intervalle — "tous les N jours" à une heure donnée : pas (*/N)
        // sur le champ jour-du-mois.
        var domStep = /^\*\/(\d{1,2})$/.exec(dom);
        if (sec === '0' && month === '*' && dow === '*'
            && isNumInRange(min, 59) && isNumInRange(hour, 23)
            && domStep && parseInt(domStep[1], 10) >= 1 && parseInt(domStep[1], 10) <= 31) {
          if (intervalUnitSelect) intervalUnitSelect.value = 'days';
          updateIntervalUnitUi(false);
          if (intervalValueInput) intervalValueInput.value = domStep[1];
          if (intervalTimeInput) intervalTimeInput.value = pad2(hour) + ':' + pad2(min);
          setMode('interval');
          return;
        }

        setMode('custom');
      })();
    });
  });

})();
