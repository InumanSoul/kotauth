SwaggerUIBundle({
  url: "/api/docs/openapi.yaml",
  dom_id: "#swagger-ui",
  deepLinking: true,
  presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
  layout: "StandaloneLayout",
  persistAuthorization: true,
  tryItOutEnabled: true,
  requestSnippetsEnabled: true,
  defaultModelsExpandDepth: 1,
  defaultModelExpandDepth: 3
})
