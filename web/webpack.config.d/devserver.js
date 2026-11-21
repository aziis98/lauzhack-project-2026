// Dev server: run on 8081 (Ktor owns 8080) and proxy API calls to the backend.
// webpack-dev-server 5 dropped the object form of `proxy` — array form required.
config.devServer = config.devServer || {};
config.devServer.port = 8081;
config.devServer.proxy = [
	{
		context: ["/api"],
		target: "http://localhost:8080",
		changeOrigin: true,
	},
];
