{{/* Expand the name of the chart. */}}
{{- define "subnetory.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Create a default fully qualified app name. */}}
{{- define "subnetory.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Create chart name and version as used by the chart label. */}}
{{- define "subnetory.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels. */}}
{{- define "subnetory.labels" -}}
helm.sh/chart: {{ include "subnetory.chart" . }}
{{ include "subnetory.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/* Selector labels. */}}
{{- define "subnetory.selectorLabels" -}}
app.kubernetes.io/name: {{ include "subnetory.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Application selector labels. */}}
{{- define "subnetory.application.selectorLabels" -}}
{{ include "subnetory.selectorLabels" . }}
app.kubernetes.io/component: application
{{- end }}

{{/* Service account name. */}}
{{- define "subnetory.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "subnetory.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- required "serviceAccount.name is required when serviceAccount.create=false" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/* PostgreSQL resource name. */}}
{{- define "subnetory.postgresql.fullname" -}}
{{- printf "%s-postgresql" (include "subnetory.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* PostgreSQL selector labels. */}}
{{- define "subnetory.postgresql.selectorLabels" -}}
{{ include "subnetory.selectorLabels" . }}
app.kubernetes.io/component: postgresql
{{- end }}

{{/* Backup resource name. */}}
{{- define "subnetory.backup.fullname" -}}
{{- $suffix := "-backups" }}
{{- $baseLength := sub 63 (len $suffix) }}
{{- printf "%s%s" ((include "subnetory.fullname" .) | trunc (int $baseLength) | trimSuffix "-") $suffix }}
{{- end }}

{{/* Backup PVC name, whether created by this chart or supplied externally. */}}
{{- define "subnetory.backup.claimName" -}}
{{- default (include "subnetory.backup.fullname" .) .Values.backup.persistence.existingClaim }}
{{- end }}

{{/* Backup script ConfigMap name. */}}
{{- define "subnetory.backup.scriptConfigMapName" -}}
{{- $suffix := "-backups-script" }}
{{- $baseLength := sub 63 (len $suffix) }}
{{- printf "%s%s" ((include "subnetory.fullname" .) | trunc (int $baseLength) | trimSuffix "-") $suffix }}
{{- end }}

{{/* Backup CronJob name with the level suffix preserved. */}}
{{- define "subnetory.backup.cronJobName" -}}
{{- $suffix := printf "-backups-%s" .level }}
{{- $baseLength := sub 63 (len $suffix) }}
{{- printf "%s%s" ((include "subnetory.fullname" .root) | trunc (int $baseLength) | trimSuffix "-") $suffix }}
{{- end }}

{{/*
In-app backup engine resource name (Phase 7 audit, 31/07/2026).
Distinct from "subnetory.backup.*" above, which names resources for the
legacy standalone CronJob mechanism (backup-cronjobs.yaml). backupApp names
the PVC used by dev.subnetory.backup.BackupExecutionService, mounted
directly into the application Deployment so pg_dump/pg_restore run inside
the app process itself — identically in Docker Compose and Kubernetes.
*/}}
{{- define "subnetory.backupApp.fullname" -}}
{{- $suffix := "-app-backups" }}
{{- $baseLength := sub 63 (len $suffix) }}
{{- printf "%s%s" ((include "subnetory.fullname" .) | trunc (int $baseLength) | trimSuffix "-") $suffix }}
{{- end }}

{{/* In-app backup PVC name, whether created by this chart or supplied externally. */}}
{{- define "subnetory.backupApp.claimName" -}}
{{- default (include "subnetory.backupApp.fullname" .) .Values.backupApp.persistence.existingClaim }}
{{- end }}
