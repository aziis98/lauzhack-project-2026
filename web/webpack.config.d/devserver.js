// Dev server: run on 8081 (Ktor owns 8080) and proxy API calls to the backend.
config.devServer = config.devServer || {};
config.devServer.port = 8081;
config.devServer.proxy = {
	"/api": {
		target: "http://localhost:8080",
		changeOrigin: true,
	},
};
