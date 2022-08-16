
#include "load_config.h"

const char entity_info_name[] = "entityInfo.name";
const char entity_info_purpose[] = "entityInfo.purpose";
const char entity_info_numkey[] = "entityInfo.number_key";
const char authinfo_pubkey_path[] = "authInfo.pubkey.path";
const char entity_info_privkey_path[] = "entityInfo.privkey.path";
const char authInfo_ip_address[] = "auth.ip.address";
const char authInfo_port[] = "auth.port.number";
const char entity_serverInfo_ip_address[] = "entity.server.ip.address";
const char entity_serverInfo_port_number[] = "entity.server.port.number";
const char network_protocol[] = "network.protocol";

int get_key_value(char *ptr)
{
    if (strcmp(ptr, entity_info_name) == 0)
        return ENTITY_INFO_NAME;
    else if (strcmp(ptr, entity_info_purpose) == 0)
        return ENTITY_INFO_PURPOSE;
    else if (strcmp(ptr, entity_info_numkey) == 0)
        return ENTITY_INFO_NUMKEY;
    else if (strcmp(ptr, authinfo_pubkey_path) == 0)
        return AUTH_INFO_PUBKEY_PATH;
    else if (strcmp(ptr, entity_info_privkey_path) == 0)
        return ENTITY_INFO_PRIVKEY_PATH;
    else if (strcmp(ptr, authInfo_ip_address) == 0)
        return AUTH_INFO_IP_ADDRESS;
    else if (strcmp(ptr, authInfo_port) == 0)
        return AUTH_INFO_PORT;
    else if (strcmp(ptr, entity_serverInfo_ip_address) == 0)
        return ENTITY_SERVER_INFO_IP_ADDRESS;
    else if (strcmp(ptr, entity_serverInfo_port_number) == 0)
        return ENTITY_SERVER_INFO_PORT_NUMBER;
    else if (strcmp(ptr, network_protocol) == 0)
        return NETWORK_PROTOCOL;
    else
        return -1;
}

config_t *load_config_t(char *path)
{
    config_t *c = malloc(sizeof(config_t));
    FILE *fp = fopen(path, "r");
    char buffer[MAX] = {
        0,
    };
    char *pline;

    printf("--config--\n");
    while (!feof(fp))
    {
        pline = fgets(buffer, MAX, fp);
        char *ptr = strtok(pline, "=");
        int a;
        while (ptr != NULL)
        {
            switch (get_key_value(ptr))
            {
            case ENTITY_INFO_NAME:
                ptr = strtok(NULL, " ");
                printf("name: %s", ptr);
                memcpy(c->name, ptr, sizeof(c->name));
                break;
            case ENTITY_INFO_PURPOSE:
                ptr = strtok(NULL, " ");
                printf("purpose: %s", ptr);
                memcpy(c->purpose, ptr, sizeof(c->purpose));
                break;
            case ENTITY_INFO_NUMKEY:
                ptr = strtok(NULL, " ");
                printf("Numkey: %s", ptr);
                memcpy(c->numkey, ptr, sizeof(c->numkey));
                break;
            case AUTH_INFO_PUBKEY_PATH:
                ptr = strtok(NULL, " ");
                printf("Pubkey path of Auth: %s", ptr);
                memcpy(c->auth_pubkey_path, ptr, sizeof(c->auth_pubkey_path));
                break;
            case ENTITY_INFO_PRIVKEY_PATH:
                ptr = strtok(NULL, " ");
                printf("Privkey path of Entity: %s", ptr);
                memcpy(c->entity_privkey_path, ptr, sizeof(c->entity_privkey_path));
                break;
            case AUTH_INFO_IP_ADDRESS:
                ptr = strtok(NULL, " ");
                printf("IP address of Auth: %s", ptr);
                memcpy(c->auth_ip_addr, ptr, sizeof(c->auth_ip_addr));
                break;
            case AUTH_INFO_PORT:
                ptr = strtok(NULL, " ");
                printf("Port number of Auth: %s", ptr);
                memcpy(c->auth_port_num, ptr, sizeof(c->auth_port_num));
                break;
            case ENTITY_SERVER_INFO_IP_ADDRESS:
                ptr = strtok(NULL, " ");
                printf("IP address of entity server: %s", ptr);
                memcpy(c->entity_server_ip_addr, ptr, sizeof(c->entity_server_ip_addr));
                break;
            case ENTITY_SERVER_INFO_PORT_NUMBER:
                ptr = strtok(NULL, " ");
                printf("Port number of entity server: %s", ptr);
                memcpy(c->entity_server_port_num, ptr, sizeof(c->entity_server_port_num));
                break;
            case NETWORK_PROTOCOL:
                ptr = strtok(NULL, " ");
                printf("Network Protocol: %s\n", ptr);
                memcpy(c->network_protocol, ptr, sizeof(c->network_protocol));
                break;
            }
            break;
        }
    }
    fclose(fp); // close file pointer.
    return c;
}
