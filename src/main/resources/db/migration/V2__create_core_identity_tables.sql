CREATE TABLE user_account (
                              id UUID NOT NULL,
                              login_id VARCHAR(64) NOT NULL,
                              password_hash VARCHAR(255) NOT NULL,
                              role VARCHAR(32) NOT NULL,
                              status VARCHAR(32) NOT NULL,
                              last_login_at TIMESTAMPTZ,
                              created_at TIMESTAMPTZ NOT NULL,
                              updated_at TIMESTAMPTZ NOT NULL,
                              CONSTRAINT pk_user_account PRIMARY KEY (id),
                              CONSTRAINT uk_user_account_login_id UNIQUE (login_id),
                              CONSTRAINT chk_user_account_login_id_not_blank CHECK (BTRIM(login_id) <> ''),
                              CONSTRAINT chk_user_account_password_hash_not_blank CHECK (BTRIM(password_hash) <> ''),
                              CONSTRAINT chk_user_account_role CHECK (role IN ('CUSTOMER', 'MANAGER', 'ADMIN')),
                              CONSTRAINT chk_user_account_status CHECK (status IN ('ACTIVE', 'LOCKED', 'INACTIVE'))
);

CREATE TABLE employee (
                          id UUID NOT NULL,
                          user_account_id UUID NOT NULL,
                          employee_number VARCHAR(32) NOT NULL,
                          full_name VARCHAR(150) NOT NULL,
                          email VARCHAR(254) NOT NULL,
                          status VARCHAR(32) NOT NULL,
                          deactivation_scheduled_at TIMESTAMPTZ,
                          created_at TIMESTAMPTZ NOT NULL,
                          updated_at TIMESTAMPTZ NOT NULL,
                          CONSTRAINT pk_employee PRIMARY KEY (id),
                          CONSTRAINT fk_employee_user_account FOREIGN KEY (user_account_id) REFERENCES user_account (id),
                          CONSTRAINT uk_employee_user_account_id UNIQUE (user_account_id),
                          CONSTRAINT uk_employee_employee_number UNIQUE (employee_number),
                          CONSTRAINT chk_employee_employee_number_not_blank CHECK (BTRIM(employee_number) <> ''),
                          CONSTRAINT chk_employee_full_name_not_blank CHECK (BTRIM(full_name) <> ''),
                          CONSTRAINT chk_employee_email_not_blank CHECK (BTRIM(email) <> ''),
                          CONSTRAINT chk_employee_status CHECK (status IN ('ACTIVE', 'DEACTIVATION_SCHEDULED', 'INACTIVE'))
);

CREATE UNIQUE INDEX uk_employee_email_lower ON employee (LOWER(email));