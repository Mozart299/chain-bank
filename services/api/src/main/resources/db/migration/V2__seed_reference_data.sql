-- Fictional reference data for the two-bank sandbox. Nothing here represents real money.
INSERT INTO currencies (code, exponent, name) VALUES ('xUGX', 0, 'Fictional Ugandan shilling (simulation only)');

INSERT INTO institutions (id, code, name, pseudonym) VALUES
    ('00000000-0000-0000-0000-00000000000a', 'BANK_A', 'Nile Demo Bank', 'INST-A'),
    ('00000000-0000-0000-0000-00000000000b', 'BANK_B', 'Rwenzori Demo Bank', 'INST-B');

-- Per bank: equity (source of seed funds), settlement (asset mirrored by on-chain tokens), suspense.
INSERT INTO accounts (id, institution_id, account_type, normal_side, allow_negative, currency, name) VALUES
    ('00000000-0000-0000-0001-00000000000a', '00000000-0000-0000-0000-00000000000a', 'EQUITY',     'CREDIT', false, 'xUGX', 'Nile Demo Bank equity'),
    ('00000000-0000-0000-0002-00000000000a', '00000000-0000-0000-0000-00000000000a', 'SETTLEMENT', 'DEBIT',  false, 'xUGX', 'Nile Demo Bank settlement'),
    ('00000000-0000-0000-0003-00000000000a', '00000000-0000-0000-0000-00000000000a', 'SUSPENSE',   'DEBIT',  true,  'xUGX', 'Nile Demo Bank suspense'),
    ('00000000-0000-0000-0001-00000000000b', '00000000-0000-0000-0000-00000000000b', 'EQUITY',     'CREDIT', false, 'xUGX', 'Rwenzori Demo Bank equity'),
    ('00000000-0000-0000-0002-00000000000b', '00000000-0000-0000-0000-00000000000b', 'SETTLEMENT', 'DEBIT',  false, 'xUGX', 'Rwenzori Demo Bank settlement'),
    ('00000000-0000-0000-0003-00000000000b', '00000000-0000-0000-0000-00000000000b', 'SUSPENSE',   'DEBIT',  true,  'xUGX', 'Rwenzori Demo Bank suspense');

-- Opening position: each bank holds 1,000,000,000 xUGX of settlement capacity funded by equity.
INSERT INTO journals (id, reference, currency, description) VALUES
    ('00000000-0000-0000-00ff-00000000000a', 'OPENING-BANK_A', 'xUGX', 'Opening settlement position'),
    ('00000000-0000-0000-00ff-00000000000b', 'OPENING-BANK_B', 'xUGX', 'Opening settlement position');

INSERT INTO journal_lines (journal_id, account_id, debit_minor, credit_minor, currency) VALUES
    ('00000000-0000-0000-00ff-00000000000a', '00000000-0000-0000-0002-00000000000a', 1000000000, 0, 'xUGX'),
    ('00000000-0000-0000-00ff-00000000000a', '00000000-0000-0000-0001-00000000000a', 0, 1000000000, 'xUGX'),
    ('00000000-0000-0000-00ff-00000000000b', '00000000-0000-0000-0002-00000000000b', 1000000000, 0, 'xUGX'),
    ('00000000-0000-0000-00ff-00000000000b', '00000000-0000-0000-0001-00000000000b', 0, 1000000000, 'xUGX');
