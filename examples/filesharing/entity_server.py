import sys
import socket
import selectors
import types
import subprocess
import time
from datetime import datetime
import secrets
bytes_num = 1024
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.backends.openssl import rsa
# from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import utils
from cryptography.hazmat.backends.openssl.backend import Backend

from Crypto.PublicKey import RSA
from Crypto.Cipher import PKCS1_v1_5
from OpenSSL.crypto import load_privatekey, FILETYPE_PEM, sign, load_publickey, verify, X509
from Crypto.Hash import SHA256 as SHA

filesystemManager_dir = {"name" : "", "purpose" : '', "number_key":"", "auth_pubkey_path":"", "privkey_path":"", "auth_ip_address":"", "auth_port_number":"", "port_number":"", "ip_address":"", "network_protocol":""}

def load_config(path):
    f = open(path, 'r')
    while True:
        line = f.readline()
        if not line: break
        if line.split("=")[0] == "name":
            filesystemManager_dir["name"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "purpose":
            filesystemManager_dir["purpose"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "number_key":
            filesystemManager_dir["number_key"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "auth_pubkey_path":
            filesystemManager_dir["auth_pubkey_path"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "privkey_path":
            filesystemManager_dir["privkey_path"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "auth_ip_address":
            filesystemManager_dir["auth_ip_address"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "auth_port_number":
            filesystemManager_dir["auth_port_number"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "port_number":
            filesystemManager_dir["port_number"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "ip_address":
            filesystemManager_dir["ip_address"] = line.split("=")[1].strip("\n")
        elif line.split("=")[0] == "network_protocol":
            filesystemManager_dir["network_protocol"] = line.split("=")[1].strip("\n")
        else:
            break
    f.close()
load_config(sys.argv[1])
print(filesystemManager_dir)

sel = selectors.DefaultSelector()

def write_in_n_bytes(num_key, key_size):
    buffer = bytearray(4)
    for i in range(key_size):
        buffer[i] = num_key >> 8*(key_size -1 -i)
    return buffer

def num_to_var_length_int(num):
    var_buf_size = 1
    buffer = bytearray(4)
    while (num > 127):
        buffer[var_buf_size-1] = 128 | num & 127
        var_buf_size += 1
        num >>=7
    buffer[var_buf_size - 1] = num
    buf = bytearray(var_buf_size)
    for i in range(var_buf_size):
        buf[i] = buffer[i]
    return buf

def accept_wrapper(sock):
    conn, addr = sock.accept()
    print(f"Accepted connection from {addr}")
    conn.setblocking(False)
    data = types.SimpleNamespace(addr=addr, inb=b"", outb=b"")
    events = selectors.EVENT_READ | selectors.EVENT_WRITE
    sel.register(conn, events, data=data)

def service_connection(key, mask):
    sock = key.fileobj
    data = key.data
    global payload_max_num
    if mask & selectors.EVENT_READ:
        recv_data = sock.recv(bytes_num)  # Should be ready to read
        if recv_data:
            print(recv_data)
            print(len(recv_data))
        # TODO: Get session key (Handshakes of entity server, OpenSSL 3.0)
            if recv_data[0] == 30:
                print("Good")
                key_id = recv_data[2:2+8]
                key_id_int = (int(key_id[5]) << 16) + (int(key_id[6]) << 8) +(int(key_id[7]))
                
                print(key_id_int)
                filesystemManager_dir["purpose"] = filesystemManager_dir["purpose"].replace("00000000", str(key_id_int))
                print(filesystemManager_dir["purpose"])
                encrypted_buf = recv_data[10:]
                print(encrypted_buf)
                print(len(encrypted_buf))
                # TODO: Request session key to Auth
                client_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                Host = filesystemManager_dir["auth_ip_address"]
                Port = int(filesystemManager_dir["auth_port_number"])
                client_sock.connect((Host, Port))
                # while(1)
                while(1):
                    recv_data_from_auth = client_sock.recv(1024)
                    if len(recv_data_from_auth) == 0:
                        continue
                    print(recv_data_from_auth)
                    
                    if recv_data_from_auth[0] == 0:
                        print("Auth Hello")
                        nonce_auth = recv_data_from_auth[6:]
                        print(len(nonce_auth))
                        nonce_entity = secrets.token_bytes(8)
                        serialize_message = bytearray(8+8+4+len(filesystemManager_dir["name"])+len(filesystemManager_dir["purpose"])+ 8)
                        serialize_message[:8] = nonce_entity
                        serialize_message[8:16] = nonce_auth
                        buffer = write_in_n_bytes(int(filesystemManager_dir["number_key"]), key_size = 4)
                        serialize_message[16:20] = buffer
                        print(serialize_message)
                        buffer_name_len = num_to_var_length_int(len(filesystemManager_dir["name"]))
                        serialize_message[20:20+len(buffer_name_len)] = buffer_name_len
                        serialize_message[21:21+len(filesystemManager_dir["name"])] = bytes.fromhex(str(filesystemManager_dir["name"]).encode('utf-8').hex())

                        buffer_purpose_len = num_to_var_length_int(len(filesystemManager_dir["purpose"]))
                        serialize_message[21+len(filesystemManager_dir["name"]):21+len(filesystemManager_dir["name"])+len(buffer_purpose_len)] = buffer_purpose_len
                        serialize_message[22+len(filesystemManager_dir["name"]):22+len(filesystemManager_dir["name"])+len(filesystemManager_dir["purpose"])] = bytes.fromhex(str(filesystemManager_dir["purpose"]).encode('utf-8').hex())
                        print(serialize_message)
                        print("Private key and Public key")
            #             private_key = rsa.generate_private_key(
            # public_exponent=65537, key_size=2048, backend=default_backend())
            #             print(private_key)
            #             pem = private_key.private_bytes(
            # encoding=serialization.Encoding.PEM,
            # format=serialization.PrivateFormat.TraditionalOpenSSL,
            # encryption_algorithm=serialization.NoEncryption()
            #             )
            #             print(pem)
                        with open(filesystemManager_dir["auth_pubkey_path"], 'rb') as pem_inn:
                            public_key = (x509.load_pem_x509_certificate(pem_inn.read(), default_backend)).public_key()
                        
                        print(public_key)
                        print(type(serialize_message))
                        ciphertext = public_key.encrypt(
                            bytes(serialize_message),
                            padding.PKCS1v15()
                        )
                        print(len(ciphertext))
                        print(ciphertext)

                        with open(filesystemManager_dir["privkey_path"], 'rb') as pem_in:
                            private_key= serialization.load_pem_private_key(pem_in.read(), None)
                        print(private_key)
                        
                        signature = private_key.sign(
                            ciphertext,
                            padding.PKCS1v15(),
                            hashes.SHA256()
                        )
                        
                        private_key.public_key().verify(
                            signature,
                            ciphertext,
                            padding.PKCS1v15(),
                            hashes.SHA256()
                        )
                        
                        print(len(signature))
                        print(signature)
                        num_buffer = num_to_var_length_int(len(ciphertext) + len(signature))
                        total_buffer = bytearray(len(num_buffer)+1+len(ciphertext) + len(signature))
                        total_buffer[0] = 20
                        total_buffer[1:1+len(num_buffer)] = num_buffer
                        total_buffer[1+len(num_buffer):1+len(num_buffer)+len(ciphertext)] = ciphertext
                        total_buffer[1+len(num_buffer)+len(ciphertext):1+len(num_buffer)+len(ciphertext)+len(signature)] = signature
                        client_sock.send(bytes(total_buffer))
                        
                    elif recv_data_from_auth[0] == 21:
                        print(recv_data_from_auth)
                        print("Success")
            elif recv_data[0] == 32:
                data.outb += "Hello"
                sent = sock.send(data.outb)
                data.outb = data.outb[sent:]
        else:
            print(f"Closing connection to {data.addr}")
            sel.unregister(sock)

host, port = filesystemManager_dir["ip_address"], int(filesystemManager_dir["port_number"])

lsock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
lsock.bind((host, port))
lsock.listen()
print(f"Listening on {(host, port)}")
lsock.setblocking(False)
sel.register(lsock, selectors.EVENT_READ, data=None)

try:
    while True:
        events = sel.select(timeout=None)
        for key, mask in events:
            if key.data is None:
                accept_wrapper(key.fileobj)
            else:
                service_connection(key, mask)
except KeyboardInterrupt:
    print("Caught keyboard interrupt, exiting")
finally:
    lsock.close()
    sel.close()
    print("Finished")


# TODO: Send the message and receive the message -> Success of the secure communication
# TODO: Apply SST to communication between file system manager and entities