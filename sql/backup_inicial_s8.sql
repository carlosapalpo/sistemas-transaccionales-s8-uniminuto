CREATE DATABASE IF NOT EXISTS sistemas_transaccionales_s8 CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS sistemas_transaccionales_s8_notificaciones CHARACTER SET utf8mb4;
USE sistemas_transaccionales_s8;
CREATE TABLE IF NOT EXISTS productos(id INT PRIMARY KEY, nombre VARCHAR(80) NOT NULL, stock INT NOT NULL CHECK(stock>=0)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS pedidos(id VARCHAR(60) PRIMARY KEY, producto INT NOT NULL, cantidad INT NOT NULL CHECK(cantidad>0), FOREIGN KEY(producto) REFERENCES productos(id)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS outbox(id VARCHAR(60) PRIMARY KEY, estado VARCHAR(15) NOT NULL, FOREIGN KEY(id) REFERENCES pedidos(id)) ENGINE=InnoDB;
INSERT IGNORE INTO productos VALUES(1,'Computador de prueba',10);
USE sistemas_transaccionales_s8_notificaciones;
CREATE TABLE IF NOT EXISTS notificaciones(evento VARCHAR(60) PRIMARY KEY, detalle VARCHAR(100) NOT NULL) ENGINE=InnoDB;
-- Esquemas nuevos para semana 8. No modifica las bases de semanas anteriores.
-- No reinicia datos existentes. Para repetir desde cero use otros esquemas de practica.
