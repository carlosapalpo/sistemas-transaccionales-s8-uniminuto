# Taller de sistemas transaccionales semana 8
Carlos Alberto Palencia Pombo. Java Maven y MySQL. Compra con outbox y receptor HTTP idempotente.

## Preparar y abrir en NetBeans
1. Instale JDK 17 o 21, MySQL 8.0 y Apache NetBeans con soporte Java Maven. JFR se incluye en el JDK. Compruebe `java -version` y `jfr --help`.
2. Ejecute `sql/backup_inicial_s8.sql` en MySQL Workbench. Crea DOS esquemas exclusivos de semana 8. No modifica las bases anteriores ni reinicia datos existentes.
3. En una terminal configure DB_USER y DB_PASSWORD con sus credenciales. Java no lee archivos .env automaticamente. Inicie NetBeans desde esa terminal para heredar el entorno o configure las variables de ejecucion en el IDE.
4. En NetBeans: File > Open Project y seleccione esta carpeta, que contiene pom.xml. Seleccione JDK 17 o 21 y espere la descarga de MySQL Connector/J.
5. Ejecute Main.java con Run File. No requiere un servidor web externo: el receptor de notificaciones inicia en un puerto local libre y se cierra al terminar.

## Consola y monitoreo
```powershell
$env:DB_USER='root'
$env:DB_PASSWORD='SU_CONTRASENA'
mvn clean compile dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
$cp='target/classes;'+(Get-Content target/classpath.txt -Raw).Trim()
java '-XX:StartFlightRecording=filename=ejecucion_s8.jfr,settings=profile,dumponexit=true' -cp $cp co.edu.uniminuto.s8.Main
jfr summary ejecucion_s8.jfr
jfr print --events uniminuto.Operacion ejecucion_s8.jfr
```
La grabacion contiene eventos de negocio con paso, resultado y duracion. Antes de compartir una grabacion nueva, filtre los eventos de negocio con `jfr scrub --include-events uniminuto.Operacion ejecucion_s8.jfr eventos_compartir.jfr`: una grabacion completa puede contener variables de entorno. La grabacion entregada en ../evidencias ya esta filtrada. El resumen de JFR muestra cantidades de eventos, no porcentajes de CPU ni tiempos de respuesta promedio. Se requieren grabaciones mas largas para investigar rendimiento bajo carga.

## Resultado inicial esperado
Con una base nueva: stock 10 a 8; intento de comprar 11 produce rollback; un pedido, un outbox ENVIADO y una notificacion. HTTP 503 deja el evento PENDIENTE; el siguiente intento obtiene 200. La repeticion de la misma clave no crea otra compra ni otra notificacion. Los IDs se generan por ejecucion, por eso volver a correr el programa crea otra compra y descuenta otras dos unidades. La demostracion requiere stock >=2. No contiene pagos reales ni envio de correos.

DB_URL y NOTIFY_DB_URL permiten cambiar las conexiones JDBC de origen y receptor. Por defecto ambos esquemas viven en MySQL local. El receptor y el cliente son componentes separados en codigo y se comunican por HTTP, pero viven en la misma JVM. No se ha probado distribucion fisica ni caida de procesos. Un unico publicador secuencial y cantidades pequeñas son limites intencionales del ejemplo. Para produccion se requieren autenticacion, TLS, roles SQL separados, backoff, reclamacion de eventos, alertas y servicio receptor independiente.

## Verificar y restaurar
```sql
SELECT * FROM sistemas_transaccionales_s8.productos;
SELECT * FROM sistemas_transaccionales_s8.pedidos;
SELECT * FROM sistemas_transaccionales_s8.outbox;
SELECT * FROM sistemas_transaccionales_s8_notificaciones.notificaciones;
```
`sql/backup_resultados_s8.sql` contiene el respaldo real posterior a las pruebas. Restaura y reemplaza las tablas de SOLO los dos esquemas s8: importelo unicamente en un entorno de practica. Incluye claves ficticias, nunca credenciales. La restauracion ya fue comprobada en esquemas separados; el resultado esta en ../evidencias/restauracion_backup.log.

## Evidencia
El informe incluye capturas de una vista de los registros reales, no pantallas del IDE. Los archivos originales estan en ../evidencias. El proyecto es importable en NetBeans, pero la ejecucion verificada se hizo con javac y java. JFR es la herramienta utilizada; Prometheus y Grafana se investigaron como alternativas, no se desplegaron.
