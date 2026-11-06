{{- define "dpi.name" -}}{{ .Chart.Name }}{{- end -}}
{{- define "dpi.fullname" -}}{{ .Release.Name }}-{{ .Chart.Name }}{{- end -}}
{{- define "dpi.labels" -}}
app.kubernetes.io/name: {{ include "dpi.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "dpi.selector" -}}
app.kubernetes.io/name: {{ include "dpi.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
