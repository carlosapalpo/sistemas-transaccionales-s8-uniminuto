package co.edu.uniminuto.s8;
import java.sql.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;
import jdk.jfr.*;

/** Demo local: compra y outbox SQL integrados con un receptor REST idempotente. */
public class Main {
 @Name("uniminuto.Operacion") @Label("Operacion transaccional")
 @Category("Taller semana 8") @StackTrace(false)
 static class Operacion extends Event {
  @Label("Paso") public String paso;
  @Label("Resultado") public String resultado;
 }
 static String env(String k,String d){return System.getenv().getOrDefault(k,d);}
 static Connection db(boolean destino) throws SQLException {
  String schema=destino?"sistemas_transaccionales_s8_notificaciones":"sistemas_transaccionales_s8";
  String key=destino?"NOTIFY_DB_URL":"DB_URL";
  return DriverManager.getConnection(env(key,"jdbc:mysql://localhost:3306/"+schema+"?serverTimezone=America/Bogota&useSSL=false&allowPublicKeyRetrieval=true"),env("DB_USER","root"),env("DB_PASSWORD",""));
 }
 static int scalar(boolean dst,String sql) throws SQLException {
  try(Connection c=db(dst); Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}
 }
 static void check(boolean ok,String msg){if(!ok)throw new IllegalStateException("FALLO: "+msg);System.out.println("VERIFICADO "+msg);}
 static void comprar(String id,int qty) throws Exception {
  if(qty<=0)throw new IllegalArgumentException("Cantidad invalida");
  Operacion e=new Operacion();e.paso="compra "+id;e.begin();
  try(Connection c=db(false)) {
   c.setAutoCommit(false);
   try {
    // Una sola fila de producto serializa las compras de esta demostracion.
    try(PreparedStatement p=c.prepareStatement("SELECT stock FROM productos WHERE id=1 FOR UPDATE");ResultSet r=p.executeQuery()){
     if(!r.next())throw new SQLException("Producto ausente");
     int stock=r.getInt(1);
     try(PreparedStatement old=c.prepareStatement("SELECT cantidad FROM pedidos WHERE id=?")){
      old.setString(1,id);try(ResultSet o=old.executeQuery()){if(o.next()){
       if(o.getInt(1)!=qty)throw new SQLException("Clave repetida con otra cantidad");
       c.rollback();e.resultado="REPETIDA";System.out.println("REINTENTO "+id+": pedido existente, stock sin cambio");return;
      }}
     }
     if(stock<qty)throw new SQLException("Stock insuficiente");
    }
    try(PreparedStatement p=c.prepareStatement("UPDATE productos SET stock=stock-? WHERE id=1")){p.setInt(1,qty);p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO pedidos VALUES(?,1,?)")){p.setString(1,id);p.setInt(2,qty);p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO outbox VALUES(?,'PENDIENTE')")){p.setString(1,id);p.executeUpdate();}
    c.commit();e.resultado="COMMIT";System.out.println("COMMIT "+id+": pedido + stock + outbox");
   }catch(Exception x){c.rollback();e.resultado="ROLLBACK";System.out.println("ROLLBACK "+id+": "+x.getMessage());throw x;}
  } finally {e.end();e.commit();}
 }
 static HttpServer receptor(int port) throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
  AtomicBoolean first=new AtomicBoolean(true);
  server.createContext("/notificaciones",ex->{
   int status=200;String response="";
   try {
    if(!ex.getRequestMethod().equals("POST")){status=405;response="Metodo no permitido";}
    else if(first.getAndSet(false)){status=503;response="Falla temporal controlada";}
    else {
     String id=new String(ex.getRequestBody().readNBytes(100),StandardCharsets.UTF_8);
     if(!id.matches("[A-Za-z0-9-]{1,60}")){status=400;response="ID invalido";}
     else try(Connection c=db(true)){
      c.setAutoCommit(false);
      try(PreparedStatement p=c.prepareStatement("INSERT IGNORE INTO notificaciones VALUES(?,'Pedido confirmado')")){
       p.setString(1,id);int n=p.executeUpdate();c.commit();response=n==1?"REGISTRADA":"DUPLICADA SIN EFECTO";
      }catch(Exception x){c.rollback();throw x;}
     }
    }
   }catch(Exception x){status=500;response="Error de persistencia";}
   byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
   ex.sendResponseHeaders(status,bytes.length);try(var out=ex.getResponseBody()){out.write(bytes);}finally{ex.close();}
  });server.start();return server;
 }
 static HttpResponse<String> enviar(int port,String id)throws Exception {
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/notificaciones"))
   .timeout(Duration.ofSeconds(3)).header("Content-Type","text/plain; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(id)).build();
  return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(request,HttpResponse.BodyHandlers.ofString());
 }
 static void publicar(int port)throws Exception {
  // Un publicador secuencial. Varios publicadores requieren reclamar filas o leases.
  try(Connection c=db(false);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id FROM outbox WHERE estado='PENDIENTE' ORDER BY id")){
   while(r.next()){
    String id=r.getString(1);Operacion e=new Operacion();e.paso="envio "+id;e.begin();
    try {
     var response=enviar(port,id);e.resultado="HTTP "+response.statusCode();
     System.out.println("REST "+id+" -> "+response.statusCode()+" "+response.body());
     if(response.statusCode()==200)try(Connection mark=db(false);PreparedStatement p=mark.prepareStatement("UPDATE outbox SET estado='ENVIADO' WHERE id=?")){p.setString(1,id);p.executeUpdate();}
    } catch(java.io.IOException|InterruptedException x){e.resultado="PENDIENTE";System.out.println("Sin respuesta: evento pendiente "+id);if(x instanceof InterruptedException){Thread.currentThread().interrupt();throw x;}}
    finally{e.end();e.commit();}
   }
  }
 }
 public static void main(String[] args)throws Exception {
  String id="s8-"+System.currentTimeMillis();int before=scalar(false,"SELECT stock FROM productos WHERE id=1");
  check(before>=2,"stock disponible para la prueba");
  int orders=scalar(false,"SELECT COUNT(*) FROM pedidos"),notes=scalar(true,"SELECT COUNT(*) FROM notificaciones");
  System.out.println("INICIO stock="+before+" pedido="+id);
  try{comprar(id+"-rechazo",before+1);throw new IllegalStateException("Debia rechazar");}catch(SQLException expected){}
  check(scalar(false,"SELECT stock FROM productos WHERE id=1")==before,"rollback conserva stock");
  comprar(id,2);comprar(id,2);
  check(scalar(false,"SELECT stock FROM productos WHERE id=1")==before-2,"reintento no descuenta dos veces");
  check(scalar(false,"SELECT COUNT(*) FROM pedidos")==orders+1,"un solo pedido nuevo");
  HttpServer server=receptor(0);int port=server.getAddress().getPort();
  try {
   publicar(port);
   check(scalar(false,"SELECT COUNT(*) FROM outbox WHERE estado='PENDIENTE'")==1,"HTTP 503 conserva el evento pendiente");
   publicar(port);
   var dup=enviar(port,id);System.out.println("REENTREGA -> "+dup.statusCode()+" "+dup.body());
   check(dup.statusCode()==200 && dup.body().equals("DUPLICADA SIN EFECTO"),"receptor acepta la repeticion sin duplicar");
   check(scalar(true,"SELECT COUNT(*) FROM notificaciones")==notes+1,"una sola notificacion nueva");
   check(scalar(false,"SELECT COUNT(*) FROM outbox WHERE estado='PENDIENTE'")==0,"sin eventos pendientes al terminar");
   System.out.println("FINAL stock="+scalar(false,"SELECT stock FROM productos WHERE id=1")+"; PRUEBAS COMPLETAS");
  }finally{server.stop(0);}
 }
}
