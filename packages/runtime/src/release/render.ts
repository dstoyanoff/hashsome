/** The files of a release, as text. Pure: `package.ts` writes them out. */

export type Target = 'plain' | 'compose' | 'helm' | 'image';
export const TARGETS: readonly Target[] = ['plain', 'compose', 'helm', 'image'];

export interface ReleaseInfo {
  /** The project's name: the image, the compose service and the chart. A valid DNS label. */
  name: string;
  tag: string;
  platform: string;
  port: number;

  /** The project's `.env.example`, shown to the person deploying. */
  envExample?: string | undefined;

  /** Whether `image.tar` is in the release. */
  image: boolean;
}

export const imageRef = ({ name, tag }: Pick<ReleaseInfo, 'name' | 'tag'>) => `${name}:${tag}`;

/** A project name made safe for an image, a service and a chart. */
export function safeName(raw: string): string {
  const name = raw
    .replace(/^@[^/]+\//, '')
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, '-')
    .replace(/^-+|-+$/g, '');

  return name || 'hashsome';
}

export function dockerfile({ port }: Pick<ReleaseInfo, 'port'>): string {
  return `# The release's server and client: nothing is installed or compiled here.
FROM node:24.22.1-slim@sha256:55fb5f5a527ea2c5c4a95f963e48ef90aa38c7b346054fdc8ee9957a2b55d205
ENV NODE_ENV=production PORT=${port}
WORKDIR /app
COPY server.mjs ./
COPY client ./client
USER node
EXPOSE ${port}
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/healthz').then((r)=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "server.mjs"]
`;
}

export function plainReadme(info: ReleaseInfo): string {
  return `# ${info.name}: plain

The whole app as files: \`server.mjs\` (the server, with your config and integrations inside it) and
\`client/\` (your dashboards). Run it anywhere Node 24 or newer is installed, no \`npm install\`:

\`\`\`sh
HA_URL=... HA_TOKEN=... node server.mjs
\`\`\`

It listens on \`PORT\` (default ${info.port}) and \`HOST\` (default 0.0.0.0). Everything the config
reads from the environment (tokens and addresses) is set when it runs; none is in these files.
\`/healthz\` answers while it is up.

The static \`client/\` alone is not enough: the server keeps the tokens, talks to your backends and
serves their artwork, and the browser only ever talks to it.

If your backend uses a private certificate authority, give Node its certificate:
\`NODE_EXTRA_CA_CERTS=/path/to/ca.crt node server.mjs\`.

\`Dockerfile\` is the one \`compose\` and \`helm\` use: \`docker build -t ${info.name} .\` here builds the
image yourself.
`;
}

export function composeFile(info: ReleaseInfo): string {
  return `# docker compose up -d   (after: docker load -i ../image.tar, and a .env next to this file)
services:
  ${info.name}:
    image: ${imageRef(info)}
    restart: unless-stopped
    ports:
      - "${info.port}:${info.port}"
    # Your tokens and addresses, read at runtime. Never baked into the image.
    env_file: .env
    # A private certificate authority for your backend:
    # environment:
    #   NODE_EXTRA_CA_CERTS: /certs/ca.crt
    # volumes:
    #   - ./ca.crt:/certs/ca.crt:ro
`;
}

export function composeReadme(info: ReleaseInfo): string {
  return `# ${info.name}: Docker Compose

1. Load the image: \`docker load -i ../image.tar\`${info.image ? '' : ' (this release has no image: build one from a `plain` release, `docker build plain`)'}
2. Create \`.env\` here with your tokens and addresses (see \`.env.example\`)
3. \`docker compose up -d\`

Update by loading the next release's image and changing the tag in \`compose.yaml\`.
`;
}

export function envExample(info: ReleaseInfo): string {
  return (
    info.envExample ??
    '# The variables your hashsome.config.ts reads, for example:\n# HA_URL=\n# HA_TOKEN=\n'
  );
}

// ── Helm ─────────────────────────────────────────────────────────────────────────────────────────

export function chartYaml(info: ReleaseInfo): string {
  return `apiVersion: v2
name: ${info.name}
description: ${info.name}, a Hashsome dashboard server
type: application
version: 0.1.0
appVersion: "${info.tag}"
`;
}

export function chartValues(info: ReleaseInfo): string {
  return `# The image is loaded onto the node from image.tar (see import-image.sh), not pulled, hence Never.
image:
  repository: ${info.name}
  tag: "${info.tag}"
  pullPolicy: Never

port: ${info.port}

# Your tokens and addresses, in a Secret you create (see NOTES). The chart never holds their values.
existingSecret: ""

# Extra environment variables that are not secret.
env: {}

# A private certificate authority for your backend: a ConfigMap holding the certificate.
caCert:
  configMap: ""
  key: ca.crt

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: false
  className: traefik
  host: ""
  # A TLS secret for the host; leave empty for plain http.
  tlsSecret: ""
  annotations: {}

resources: {}
nodeSelector: {}
`;
}

export function chartHelpers(): string {
  return `{{- define "app.name" -}}{{ .Release.Name }}{{- end -}}
{{- define "app.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "app.selector" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
`;
}

export function chartDeployment(): string {
  return `apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  # One replica, and the old pod goes before the new one starts: the server holds live
  # connections to your backends, and two at once would just double them.
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      {{- include "app.selector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "app.selector" . | nindent 8 }}
    spec:
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.port }}
          envFrom:
            - secretRef:
                name: {{ required "set existingSecret to the Secret holding your tokens" .Values.existingSecret }}
          env:
            - name: PORT
              value: {{ .Values.port | quote }}
            {{- if .Values.caCert.configMap }}
            - name: NODE_EXTRA_CA_CERTS
              value: /certs/{{ .Values.caCert.key }}
            {{- end }}
            {{- range $name, $value := .Values.env }}
            - name: {{ $name }}
              value: {{ $value | quote }}
            {{- end }}
          readinessProbe:
            httpGet:
              path: /healthz
              port: http
          livenessProbe:
            httpGet:
              path: /healthz
              port: http
            initialDelaySeconds: 10
          {{- with .Values.resources }}
          resources:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if .Values.caCert.configMap }}
          volumeMounts:
            - name: ca
              mountPath: /certs
              readOnly: true
          {{- end }}
      {{- if .Values.caCert.configMap }}
      volumes:
        - name: ca
          configMap:
            name: {{ .Values.caCert.configMap }}
      {{- end }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
`;
}

export function chartService(): string {
  return `apiVersion: v1
kind: Service
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "app.selector" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
`;
}

export function chartIngress(): string {
  return `{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "app.name" . }}
  labels:
    {{- include "app.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- if .Values.ingress.className }}
  ingressClassName: {{ .Values.ingress.className }}
  {{- end }}
  {{- if .Values.ingress.tlsSecret }}
  tls:
    - hosts:
        - {{ required "set ingress.host" .Values.ingress.host }}
      secretName: {{ .Values.ingress.tlsSecret }}
  {{- end }}
  rules:
    - host: {{ required "set ingress.host" .Values.ingress.host }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ include "app.name" . }}
                port:
                  name: http
{{- end }}
`;
}

export function chartNotes(): string {
  return `{{ .Release.Name }} is deployed.

Your tokens and addresses come from the Secret "{{ .Values.existingSecret }}". Create it once, with the
variables your hashsome.config.ts reads (it is not part of this chart, so it is not in git):

  kubectl create secret generic {{ .Values.existingSecret }} -n {{ .Release.Namespace }} \\
    --from-literal=HA_URL=https://... --from-literal=HA_TOKEN=...

To change a token later, update the Secret and run: kubectl rollout restart deploy/{{ include "app.name" . }} -n {{ .Release.Namespace }}
{{- if .Values.ingress.enabled }}

It is served at http{{ if .Values.ingress.tlsSecret }}s{{ end }}://{{ .Values.ingress.host }}/
{{- end }}
`;
}

export function importScript(info: ReleaseInfo): string {
  return `#!/bin/sh
# Loads the release's image into k3s's containerd on this machine. Run it on the server, from here.
set -eu
cd "$(dirname "$0")"
sudo k3s ctr -n k8s.io images import ../image.tar
echo "Imported ${imageRef(info)}"
`;
}

export function helmReadme(info: ReleaseInfo): string {
  return `# ${info.name}: Helm (k3s)

This folder is a Helm chart. Put it in your cluster's folder and add it to your helmfile.

1. On the server, load the image into k3s: \`./import-image.sh\`
   (it imports \`../image.tar\`, so keep the release folder together, or edit the path)
2. Create the Secret with your tokens, once (see \`templates/NOTES.txt\`):
   \`kubectl create secret generic ${info.name}-secrets --from-literal=HA_URL=... --from-literal=HA_TOKEN=...\`
3. Add a release to your helmfile:

\`\`\`yaml
releases:
  - name: ${info.name}
    chart: ./${info.name}      # this folder
    values:
      - existingSecret: ${info.name}-secrets
        ingress:
          enabled: true
          host: ${info.name}.lan
\`\`\`

4. \`helmfile apply\`

The image tag is \`${info.tag}\` (in \`values.yaml\`). A new release has a new tag, so k3s always picks
up the update. Built for \`${info.platform}\`; \`pullPolicy\` is \`Never\` because the image is imported,
not pulled.
`;
}

export function releaseReadme(info: ReleaseInfo, targets: readonly Target[]): string {
  const lines: Record<Target, string> = {
    plain:
      '- `plain/`: the server bundle and the client. Runs with just Node, or builds into an image.',
    compose: '- `compose/`: Docker Compose, for a machine running Docker.',
    helm: '- `helm/`: a Helm chart for k3s/Kubernetes (loads the image, no registry).',
    image: '- `image.tar`: just the container image, for your own manifests.',
  };

  return `# ${info.name} ${info.tag}

Built for \`${info.platform}\`. Deploy it as:

${targets.map((t) => lines[t]).join('\n')}
${info.image && !targets.includes('image') ? `- \`image.tar\`: the container image (\`${imageRef(info)}\`), for compose and helm.\n` : ''}
Your tokens and addresses are not in any of these files. They are set where it runs: an \`.env\`
file for compose, a Kubernetes Secret for helm, the environment for plain.
`;
}
