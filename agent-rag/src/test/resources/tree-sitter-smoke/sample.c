typedef struct Server {
  int port;
} Server;

int start_server(Server *server) {
  return server->port;
}
