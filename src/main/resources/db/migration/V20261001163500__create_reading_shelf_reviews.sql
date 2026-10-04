CREATE TABLE reading_shelf (
    user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    book_id bigint NOT NULL REFERENCES book(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL CHECK (status IN ('WANT_TO_READ','READING','FINISHED')),
    note varchar(1000) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,book_id)
);
CREATE INDEX reading_shelf_updated_idx ON reading_shelf(user_id,updated_at DESC,book_id DESC);

-- Reviews intentionally survive removal from a reading shelf.
CREATE TABLE book_review (
    user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    book_id bigint NOT NULL REFERENCES book(id) ON DELETE CASCADE,
    difficulty varchar(20) NOT NULL CHECK (difficulty IN ('EASY','APPROPRIATE','HARD')),
    text varchar(300) NOT NULL CHECK (btrim(text) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,book_id)
);
CREATE INDEX book_review_updated_idx ON book_review(book_id,updated_at DESC,user_id DESC);
