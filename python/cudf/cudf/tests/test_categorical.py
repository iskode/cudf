# Copyright (c) 2018-2021, NVIDIA CORPORATION.

import operator
import string

import numpy as np
import pandas as pd
import pytest

import cudf
from cudf.core._compat import PANDAS_GE_110
from cudf.testing._utils import (
    NUMERIC_TYPES,
    assert_eq,
    assert_exceptions_equal,
)


@pytest.fixture
def pd_str_cat():
    categories = list("abc")
    codes = [0, 0, 1, 0, 1, 2, 0, 1, 1, 2]
    return pd.Categorical.from_codes(codes, categories=categories)


def test_categorical_basic():
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    cudf_cat = cudf.Index(cat)

    pdsr = pd.Series(cat, index=["p", "q", "r", "s", "t"])
    sr = cudf.Series(cat, index=["p", "q", "r", "s", "t"])
    assert_eq(pdsr.cat.codes, sr.cat.codes, check_dtype=False)

    # Test attributes
    assert_eq(pdsr.cat.categories, sr.cat.categories)
    assert pdsr.cat.ordered == sr.cat.ordered

    np.testing.assert_array_equal(
        pdsr.cat.codes.values, sr.cat.codes.to_array()
    )

    string = str(sr)
    expect_str = """
p a
q a
r b
s c
t a
"""
    assert all(x == y for x, y in zip(string.split(), expect_str.split()))
    assert_eq(cat.codes, cudf_cat.codes.to_array())


def test_categorical_integer():
    if not PANDAS_GE_110:
        pytest.xfail(reason="pandas >=1.1 required")
    cat = pd.Categorical(["a", "_", "_", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)
    np.testing.assert_array_equal(
        cat.codes, sr.cat.codes.astype(cat.codes.dtype).fillna(-1).to_array()
    )
    assert sr.null_count == 2

    np.testing.assert_array_equal(
        pdsr.cat.codes.values,
        sr.cat.codes.astype(pdsr.cat.codes.dtype).fillna(-1).to_array(),
    )

    string = str(sr)
    expect_str = """
0 a
1 <NA>
2 <NA>
3 c
4 a
dtype: category
Categories (3, object): ['a', 'b', 'c']
"""
    assert string.split() == expect_str.split()


def test_categorical_compare_unordered():
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)

    sr = cudf.Series(cat)

    # test equal
    out = sr == sr
    assert out.dtype == np.bool_
    assert type(out[0]) == np.bool_
    assert np.all(out.to_array())
    assert np.all(pdsr == pdsr)

    # test inequality
    out = sr != sr
    assert not np.any(out.to_array())
    assert not np.any(pdsr != pdsr)

    assert not pdsr.cat.ordered
    assert not sr.cat.ordered

    # test using ordered operators
    assert_exceptions_equal(
        lfunc=operator.lt,
        rfunc=operator.lt,
        lfunc_args_and_kwargs=([pdsr, pdsr],),
        rfunc_args_and_kwargs=([sr, sr],),
    )


def test_categorical_compare_ordered():
    cat1 = pd.Categorical(
        ["a", "a", "b", "c", "a"], categories=["a", "b", "c"], ordered=True
    )
    pdsr1 = pd.Series(cat1)
    sr1 = cudf.Series(cat1)
    cat2 = pd.Categorical(
        ["a", "b", "a", "c", "b"], categories=["a", "b", "c"], ordered=True
    )
    pdsr2 = pd.Series(cat2)
    sr2 = cudf.Series(cat2)

    # test equal
    out = sr1 == sr1
    assert out.dtype == np.bool_
    assert type(out[0]) == np.bool_
    assert np.all(out.to_array())
    assert np.all(pdsr1 == pdsr1)

    # test inequality
    out = sr1 != sr1
    assert not np.any(out.to_array())
    assert not np.any(pdsr1 != pdsr1)

    assert pdsr1.cat.ordered
    assert sr1.cat.ordered

    # test using ordered operators
    np.testing.assert_array_equal(pdsr1 < pdsr2, (sr1 < sr2).to_array())
    np.testing.assert_array_equal(pdsr1 > pdsr2, (sr1 > sr2).to_array())


def test_categorical_binary_add():
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)

    assert_exceptions_equal(
        lfunc=operator.add,
        rfunc=operator.add,
        lfunc_args_and_kwargs=([pdsr, pdsr],),
        rfunc_args_and_kwargs=([sr, sr],),
        expected_error_message="Series of dtype `category` cannot perform "
        "the operation: add",
    )


def test_categorical_unary_ceil():
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)

    assert_exceptions_equal(
        lfunc=getattr,
        rfunc=sr.ceil,
        lfunc_args_and_kwargs=([pdsr, "ceil"],),
        check_exception_type=False,
        expected_error_message="Series of dtype `category` cannot "
        "perform the operation: ceil",
    )


def test_categorical_element_indexing():
    """
    Element indexing to a cat column must give the underlying object
    not the numerical index.
    """
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)
    assert_eq(pdsr, sr)
    assert_eq(pdsr.cat.codes, sr.cat.codes, check_dtype=False)


def test_categorical_masking():
    """
    Test common operation for getting a all rows that matches a certain
    category.
    """
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)

    # check scalar comparison
    expect_matches = pdsr == "a"
    got_matches = sr == "a"

    np.testing.assert_array_equal(
        expect_matches.values, got_matches.to_array()
    )

    # mask series
    expect_masked = pdsr[expect_matches]
    got_masked = sr[got_matches]

    assert len(expect_masked) == len(got_masked)
    assert len(expect_masked) == got_masked.valid_count
    assert_eq(got_masked, expect_masked)


def test_df_cat_set_index():
    df = cudf.DataFrame()
    df["a"] = pd.Categorical(list("aababcabbc"), categories=list("abc"))
    df["b"] = np.arange(len(df))
    got = df.set_index("a")

    pddf = df.to_pandas(nullable_pd_dtype=False)
    expect = pddf.set_index("a")

    assert_eq(got, expect)


def test_df_cat_sort_index():
    df = cudf.DataFrame()
    df["a"] = pd.Categorical(list("aababcabbc"), categories=list("abc"))
    df["b"] = np.arange(len(df))

    got = df.set_index("a").sort_index()
    expect = df.to_pandas(nullable_pd_dtype=False).set_index("a").sort_index()

    assert_eq(got, expect)


def test_cat_series_binop_error():
    df = cudf.DataFrame()
    df["a"] = pd.Categorical(list("aababcabbc"), categories=list("abc"))
    df["b"] = np.arange(len(df))

    dfa = df["a"]
    dfb = df["b"]

    # lhs is a categorical
    assert_exceptions_equal(
        lfunc=operator.add,
        rfunc=operator.add,
        lfunc_args_and_kwargs=([dfa, dfb],),
        rfunc_args_and_kwargs=([dfa, dfb],),
        check_exception_type=False,
        expected_error_message="Series of dtype `category` cannot "
        "perform the operation: add",
    )
    # if lhs is a numerical
    assert_exceptions_equal(
        lfunc=operator.add,
        rfunc=operator.add,
        lfunc_args_and_kwargs=([dfb, dfa],),
        rfunc_args_and_kwargs=([dfb, dfa],),
        check_exception_type=False,
        expected_error_message="'add' operator not supported",
    )


@pytest.mark.parametrize("num_elements", [10, 100, 1000])
def test_categorical_unique(num_elements):
    # create categorical series
    np.random.seed(12)
    pd_cat = pd.Categorical(
        pd.Series(
            np.random.choice(
                list(string.ascii_letters + string.digits), num_elements
            ),
            dtype="category",
        )
    )

    # gdf
    gdf = cudf.DataFrame()
    gdf["a"] = cudf.Series.from_categorical(pd_cat)
    gdf_unique_sorted = np.sort(gdf["a"].unique().to_pandas())

    # pandas
    pdf = pd.DataFrame()
    pdf["a"] = pd_cat
    pdf_unique_sorted = np.sort(pdf["a"].unique())

    # verify
    np.testing.assert_array_equal(pdf_unique_sorted, gdf_unique_sorted)


@pytest.mark.parametrize("nelem", [20, 50, 100])
def test_categorical_unique_count(nelem):
    # create categorical series
    np.random.seed(12)
    pd_cat = pd.Categorical(
        pd.Series(
            np.random.choice(
                list(string.ascii_letters + string.digits), nelem
            ),
            dtype="category",
        )
    )

    # gdf
    gdf = cudf.DataFrame()
    gdf["a"] = cudf.Series.from_categorical(pd_cat)
    gdf_unique_count = gdf["a"].nunique()

    # pandas
    pdf = pd.DataFrame()
    pdf["a"] = pd_cat
    pdf_unique = pdf["a"].unique()

    # verify
    assert gdf_unique_count == len(pdf_unique)


def test_categorical_empty():
    cat = pd.Categorical([])
    pdsr = pd.Series(cat)
    sr = cudf.Series(cat)
    np.testing.assert_array_equal(cat.codes, sr.cat.codes.to_array())

    # Test attributes
    assert_eq(pdsr.cat.categories, sr.cat.categories)
    assert pdsr.cat.ordered == sr.cat.ordered

    np.testing.assert_array_equal(
        pdsr.cat.codes.values, sr.cat.codes.to_array()
    )


def test_categorical_set_categories():
    cat = pd.Categorical(["a", "a", "b", "c", "a"], categories=["a", "b", "c"])
    psr = pd.Series(cat)
    sr = cudf.Series.from_categorical(cat)

    # adding category
    expect = psr.cat.set_categories(["a", "b", "c", "d"])
    got = sr.cat.set_categories(["a", "b", "c", "d"])
    assert_eq(expect, got)

    # removing category
    expect = psr.cat.set_categories(["a", "b"])
    got = sr.cat.set_categories(["a", "b"])
    assert_eq(expect, got)


def test_categorical_set_categories_preserves_order():
    series = pd.Series([1, 0, 0, 0, 2]).astype("category")
    # reassigning categories should preserve element ordering
    assert_eq(
        series.cat.set_categories([1, 2]),
        cudf.Series(series).cat.set_categories([1, 2]),
    )


@pytest.mark.parametrize("inplace", [True, False])
def test_categorical_as_ordered(pd_str_cat, inplace):

    pd_sr = pd.Series(pd_str_cat.copy().set_ordered(False))
    cd_sr = cudf.Series(pd_str_cat.copy().set_ordered(False))

    assert cd_sr.cat.ordered is False
    assert cd_sr.cat.ordered == pd_sr.cat.ordered

    pd_sr_1 = pd_sr.cat.as_ordered(inplace=inplace)
    cd_sr_1 = cd_sr.cat.as_ordered(inplace=inplace)
    pd_sr_1 = pd_sr if pd_sr_1 is None else pd_sr_1
    cd_sr_1 = cd_sr if cd_sr_1 is None else cd_sr_1

    assert cd_sr_1.cat.ordered is True
    assert cd_sr_1.cat.ordered == pd_sr_1.cat.ordered
    assert str(cd_sr_1) == str(pd_sr_1)


@pytest.mark.parametrize("inplace", [True, False])
def test_categorical_as_unordered(pd_str_cat, inplace):

    pd_sr = pd.Series(pd_str_cat.copy().set_ordered(True))
    cd_sr = cudf.Series(pd_str_cat.copy().set_ordered(True))

    assert cd_sr.cat.ordered is True
    assert cd_sr.cat.ordered == pd_sr.cat.ordered

    pd_sr_1 = pd_sr.cat.as_unordered(inplace=inplace)
    cd_sr_1 = cd_sr.cat.as_unordered(inplace=inplace)
    pd_sr_1 = pd_sr if pd_sr_1 is None else pd_sr_1
    cd_sr_1 = cd_sr if cd_sr_1 is None else cd_sr_1

    assert cd_sr_1.cat.ordered is False
    assert cd_sr_1.cat.ordered == pd_sr_1.cat.ordered
    assert str(cd_sr_1) == str(pd_sr_1)


@pytest.mark.parametrize("from_ordered", [True, False])
@pytest.mark.parametrize("to_ordered", [True, False])
@pytest.mark.parametrize("inplace", [True, False])
def test_categorical_reorder_categories(
    pd_str_cat, from_ordered, to_ordered, inplace
):

    pd_sr = pd.Series(pd_str_cat.copy().set_ordered(from_ordered))
    cd_sr = cudf.Series(pd_str_cat.copy().set_ordered(from_ordered))

    assert_eq(pd_sr, cd_sr)

    assert str(pd_sr) == str(cd_sr)

    kwargs = dict(ordered=to_ordered, inplace=inplace)

    pd_sr_1 = pd_sr.cat.reorder_categories(list("cba"), **kwargs)
    cd_sr_1 = cd_sr.cat.reorder_categories(list("cba"), **kwargs)
    pd_sr_1 = pd_sr if pd_sr_1 is None else pd_sr_1
    cd_sr_1 = cd_sr if cd_sr_1 is None else cd_sr_1

    assert_eq(pd_sr_1, cd_sr_1)

    assert str(cd_sr_1) == str(pd_sr_1)


@pytest.mark.parametrize("inplace", [True, False])
def test_categorical_add_categories(pd_str_cat, inplace):

    pd_sr = pd.Series(pd_str_cat.copy())
    cd_sr = cudf.Series(pd_str_cat.copy())

    assert_eq(pd_sr, cd_sr)

    assert str(pd_sr) == str(cd_sr)

    pd_sr_1 = pd_sr.cat.add_categories(["d"], inplace=inplace)
    cd_sr_1 = cd_sr.cat.add_categories(["d"], inplace=inplace)
    pd_sr_1 = pd_sr if pd_sr_1 is None else pd_sr_1
    cd_sr_1 = cd_sr if cd_sr_1 is None else cd_sr_1

    assert "d" in pd_sr_1.cat.categories.to_list()
    assert "d" in cd_sr_1.cat.categories.to_pandas().to_list()

    assert_eq(pd_sr_1, cd_sr_1)


@pytest.mark.parametrize("inplace", [True, False])
def test_categorical_remove_categories(pd_str_cat, inplace):

    pd_sr = pd.Series(pd_str_cat.copy())
    cd_sr = cudf.Series(pd_str_cat.copy())

    assert_eq(pd_sr, cd_sr)

    assert str(pd_sr) == str(cd_sr)

    pd_sr_1 = pd_sr.cat.remove_categories(["a"], inplace=inplace)
    cd_sr_1 = cd_sr.cat.remove_categories(["a"], inplace=inplace)
    pd_sr_1 = pd_sr if pd_sr_1 is None else pd_sr_1
    cd_sr_1 = cd_sr if cd_sr_1 is None else cd_sr_1

    assert "a" not in pd_sr_1.cat.categories.to_list()
    assert "a" not in cd_sr_1.cat.categories.to_pandas().to_list()

    assert_eq(pd_sr_1, cd_sr_1)

    # test using ordered operators
    assert_exceptions_equal(
        lfunc=cd_sr.to_pandas().cat.remove_categories,
        rfunc=cd_sr.cat.remove_categories,
        lfunc_args_and_kwargs=([["a", "d"]], {"inplace": inplace}),
        rfunc_args_and_kwargs=([["a", "d"]], {"inplace": inplace}),
        expected_error_message="removals must all be in old categories",
    )


def test_categorical_dataframe_slice_copy():
    pdf = pd.DataFrame({"g": pd.Series(["a", "b", "z"], dtype="category")})
    gdf = cudf.from_pandas(pdf)

    exp = pdf[1:].copy()
    gdf = gdf[1:].copy()

    assert_eq(exp, gdf)


@pytest.mark.parametrize(
    "data",
    [
        pd.Series([1, 2, 3, 89]),
        pd.Series([1, 2, 3, 89, 3, 1, 89], dtype="category"),
        pd.Series(["1", "2", "3", "4", "5"], dtype="category"),
        pd.Series(["1.0", "2.5", "3.001", "9"], dtype="category"),
        pd.Series(["1", "2", "3", None, "4", "5"], dtype="category"),
        pd.Series(["1.0", "2.5", "3.001", None, "9"], dtype="category"),
        pd.Series(["a", "b", "c", "c", "b", "a", "b", "b"]),
        pd.Series(["aa", "b", "c", "c", "bb", "bb", "a", "b", "b"]),
        pd.Series([1, 2, 3, 89, None, np.nan, np.NaN], dtype="float64"),
        pd.Series([1, 2, 3, 89], dtype="float64"),
        pd.Series([1, 2.5, 3.001, 89], dtype="float64"),
        pd.Series([None, None, None]),
        pd.Series([], dtype="float64"),
    ],
)
@pytest.mark.parametrize(
    "cat_type",
    [
        pd.CategoricalDtype(categories=["aa", "bb", "cc"]),
        pd.CategoricalDtype(categories=[2, 4, 10, 100]),
        pd.CategoricalDtype(categories=["aa", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "b", "c"]),
        pd.CategoricalDtype(categories=["1", "2", "3", "4"]),
        pd.CategoricalDtype(categories=["1.0", "2.5", "3.001", "9"]),
        pd.CategoricalDtype(categories=[]),
    ],
)
def test_categorical_typecast(data, cat_type):
    pd_data = data.copy()
    gd_data = cudf.from_pandas(data)

    assert_eq(pd_data.astype(cat_type), gd_data.astype(cat_type))


@pytest.mark.parametrize(
    "data",
    [
        pd.Series([1, 2, 3, 89]),
        pd.Series(["a", "b", "c", "c", "b", "a", "b", "b"]),
        pd.Series(["aa", "b", "c", "c", "bb", "bb", "a", "b", "b"]),
        pd.Series([1, 2, 3, 89, None, np.nan, np.NaN], dtype="float64"),
        pd.Series([1, 2, 3, 89], dtype="float64"),
        pd.Series([1, 2.5, 3.001, 89], dtype="float64"),
        pd.Series([None, None, None]),
        pd.Series([], dtype="float64"),
    ],
)
@pytest.mark.parametrize(
    "new_categories",
    [
        ["aa", "bb", "cc"],
        [2, 4, 10, 100],
        ["aa", "bb", "c"],
        ["a", "bb", "c"],
        ["a", "b", "c"],
        [],
        pd.Series(["a", "b", "c"]),
        pd.Series(["a", "b", "c"], dtype="category"),
        pd.Series([-100, 10, 11, 0, 1, 2], dtype="category"),
    ],
)
def test_categorical_set_categories_categoricals(data, new_categories):
    pd_data = data.copy().astype("category")
    gd_data = cudf.from_pandas(pd_data)

    assert_eq(
        pd_data.cat.set_categories(new_categories=new_categories),
        gd_data.cat.set_categories(new_categories=new_categories),
    )

    assert_eq(
        pd_data.cat.set_categories(
            new_categories=pd.Series(new_categories, dtype="category")
        ),
        gd_data.cat.set_categories(
            new_categories=cudf.Series(new_categories, dtype="category")
        ),
    )


@pytest.mark.parametrize(
    "data",
    [
        [1, 2, 3, 4],
        ["a", "1", "2", "1", "a"],
        pd.Series(["a", "1", "22", "1", "aa"]),
        pd.Series(["a", "1", "22", "1", "aa"], dtype="category"),
        pd.Series([1, 2, 3, -4], dtype="int64"),
        pd.Series([1, 2, 3, 4], dtype="uint64"),
        pd.Series([1, 2.3, 3, 4], dtype="float"),
        [None, 1, None, 2, None],
        [],
    ],
)
@pytest.mark.parametrize(
    "dtype",
    [
        pd.CategoricalDtype(categories=["aa", "bb", "cc"]),
        pd.CategoricalDtype(categories=[2, 4, 10, 100]),
        pd.CategoricalDtype(categories=["aa", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "b", "c"]),
        pd.CategoricalDtype(categories=["22", "b", "c"]),
        pd.CategoricalDtype(categories=[]),
    ],
)
def test_categorical_creation(data, dtype):
    expected = pd.Series(data, dtype=dtype)
    got = cudf.Series(data, dtype=dtype)
    assert_eq(expected, got)

    got = cudf.Series(data, dtype=cudf.from_pandas(dtype))
    assert_eq(expected, got)

    expected = pd.Series(data, dtype="category")
    got = cudf.Series(data, dtype="category")
    assert_eq(expected, got)


@pytest.mark.parametrize(
    "categories",
    [
        [],
        [1, 2, 3],
        pd.Series(["a", "c", "b"], dtype="category"),
        pd.Series([1, 2, 3, 4, -100], dtype="category"),
    ],
)
@pytest.mark.parametrize("ordered", [True, False])
def test_categorical_dtype(categories, ordered):
    expected = pd.CategoricalDtype(categories=categories, ordered=ordered)
    got = cudf.CategoricalDtype(categories=categories, ordered=ordered)
    assert_eq(expected, got)


@pytest.mark.parametrize(
    ("data", "expected"),
    [
        (cudf.Series([1]), np.uint8),
        (cudf.Series([1, None]), np.uint8),
        (cudf.Series(np.arange(np.iinfo(np.int8).max)), np.uint8),
        (
            cudf.Series(np.append(np.arange(np.iinfo(np.int8).max), [None])),
            np.uint8,
        ),
        (cudf.Series(np.arange(np.iinfo(np.int16).max)), np.uint16),
        (
            cudf.Series(np.append(np.arange(np.iinfo(np.int16).max), [None])),
            np.uint16,
        ),
        (cudf.Series(np.arange(np.iinfo(np.uint8).max)), np.uint8),
        (
            cudf.Series(np.append(np.arange(np.iinfo(np.uint8).max), [None])),
            np.uint8,
        ),
        (cudf.Series(np.arange(np.iinfo(np.uint16).max)), np.uint16),
        (
            cudf.Series(np.append(np.arange(np.iinfo(np.uint16).max), [None])),
            np.uint16,
        ),
    ],
)
def test_astype_dtype(data, expected):
    got = data.astype("category").cat.codes.dtype
    np.testing.assert_equal(got, expected)


@pytest.mark.parametrize(
    "data,add",
    [
        ([1, 2, 3], [100, 11, 12]),
        ([1, 2, 3], [0.01, 9.7, 15.0]),
        ([0.0, 6.7, 10.0], [100, 11, 12]),
        ([0.0, 6.7, 10.0], [0.01, 9.7, 15.0]),
        (["a", "bd", "ef"], ["asdfsdf", "bddf", "eff"]),
        ([1, 2, 3], []),
        ([0.0, 6.7, 10.0], []),
        (["a", "bd", "ef"], []),
    ],
)
def test_add_categories(data, add):
    pds = pd.Series(data, dtype="category")
    gds = cudf.Series(data, dtype="category")

    expected = pds.cat.add_categories(add)
    actual = gds.cat.add_categories(add)
    assert_eq(
        expected.cat.codes, actual.cat.codes.astype(expected.cat.codes.dtype)
    )

    # Need to type-cast pandas object to str due to mixed-type
    # support in "object"
    assert_eq(
        expected.cat.categories.astype("str")
        if (expected.cat.categories.dtype == "object")
        else expected.cat.categories,
        actual.cat.categories,
    )


@pytest.mark.parametrize(
    "data,add",
    [
        ([1, 2, 3], [1, 3, 11]),
        ([0.0, 6.7, 10.0], [1, 2, 0.0]),
        (["a", "bd", "ef"], ["a", "bd", "a"]),
    ],
)
def test_add_categories_error(data, add):
    pds = pd.Series(data, dtype="category")
    gds = cudf.Series(data, dtype="category")

    assert_exceptions_equal(
        pds.cat.add_categories,
        gds.cat.add_categories,
        ([add],),
        ([add],),
        compare_error_message=False,
    )


def test_add_categories_mixed_error():
    gds = cudf.Series(["a", "bd", "ef"], dtype="category")

    with pytest.raises(TypeError):
        gds.cat.add_categories([1, 2, 3])

    gds = cudf.Series([1, 2, 3], dtype="category")

    with pytest.raises(TypeError):
        gds.cat.add_categories(["a", "bd", "ef"])


@pytest.mark.parametrize(
    "data",
    [
        [1, 2, 3, 4],
        ["a", "1", "2", "1", "a"],
        pd.Series(["a", "1", "22", "1", "aa"]),
        pd.Series(["a", "1", "22", "1", "aa"], dtype="category"),
        pd.Series([1, 2, 3, 4], dtype="int64"),
        pd.Series([1, 2.3, 3, 4], dtype="float"),
        [None, 1, None, 2, None],
        ["a"],
    ],
)
@pytest.mark.parametrize(
    "cat_dtype",
    [
        pd.CategoricalDtype(categories=["aa", "bb", "cc"]),
        pd.CategoricalDtype(categories=[2, 4, 10, 100]),
        pd.CategoricalDtype(categories=["aa", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "bb", "c"]),
        pd.CategoricalDtype(categories=["a", "b", "c"]),
        pd.CategoricalDtype(categories=["22", "b", "c"]),
        pd.CategoricalDtype(categories=["a"]),
    ],
)
def test_categorical_assignment(data, cat_dtype):
    pd_df = pd.DataFrame()
    pd_df["a"] = np.ones(len(data))
    cd_df = cudf.from_pandas(pd_df)

    pd_cat_series = pd.Series(data, dtype=cat_dtype)
    # assign categorical series
    pd_df.assign(cat_col=pd_cat_series)
    cd_df.assign(cat_col=pd_cat_series)
    assert_eq(pd_df, cd_df)

    # assign categorical array
    # needed for dask_cudf support for including file name
    # as a categorical column
    # see issue: https://github.com/rapidsai/cudf/issues/2269
    pd_df = pd.DataFrame()
    pd_df["a"] = np.ones(len(data))
    cd_df = cudf.from_pandas(pd_df)

    pd_categorical = pd.Categorical(data, dtype=cat_dtype)
    pd_df.assign(cat_col=pd_categorical)
    cd_df.assign(cat_col=pd_categorical)
    assert_eq(pd_df, cd_df)


def test_categorical_allow_nan():
    gs = cudf.Series([1, 2, np.nan, 10, np.nan, None], nan_as_null=False)
    gs = gs.astype("category")
    expected_codes = cudf.Series([0, 1, 3, 2, 3, None], dtype="uint8")
    assert_eq(expected_codes, gs.cat.codes)

    expected_categories = cudf.Index([1.0, 2.0, 10.0, np.nan], dtype="float64")
    assert_eq(expected_categories, gs.cat.categories)

    actual_ps = gs.to_pandas()
    expected_ps = pd.Series(
        [1.0, 2.0, np.nan, 10.0, np.nan, np.nan], dtype="category"
    )
    assert_eq(actual_ps, expected_ps)


def test_categorical_setitem_with_nan():
    gs = cudf.Series(
        [1, 2, np.nan, 10, np.nan, None], nan_as_null=False
    ).astype("category")
    gs[[1, 3]] = np.nan

    expected_series = cudf.Series(
        [1, np.nan, np.nan, np.nan, np.nan, None], nan_as_null=False
    ).astype(gs.dtype)
    assert_eq(gs, expected_series)


@pytest.mark.parametrize("dtype", list(NUMERIC_TYPES) + ["object"])
@pytest.mark.parametrize("input_obj", [[1, cudf.NA, 3]])
def test_series_construction_with_nulls(input_obj, dtype):
    dtype = np.dtype(dtype)
    input_obj = [
        dtype.type(v) if v is not cudf.NA else cudf.NA for v in input_obj
    ]

    expect = pd.Series(input_obj, dtype="category")
    got = cudf.Series(input_obj, dtype="category").to_pandas()

    assert_eq(expect, got)


@pytest.mark.parametrize(
    "data",
    [
        {"a": cudf.Series(["a", "b", "c", "a", "c", "b"]).astype("category")},
        {
            "a": cudf.Series(["a", "a", "b", "b"]).astype("category"),
            "b": cudf.Series(["b", "b", "c", "c"]).astype("category"),
            "c": cudf.Series(["c", "c", "a", "a"]).astype("category"),
        },
        {
            "a": cudf.Series(["a", None, "b", "b"]).astype("category"),
            "b": cudf.Series(["b", "b", None, "c"]).astype("category"),
            "c": cudf.Series(["c", "c", "a", None]).astype("category"),
        },
    ],
)
def test_serialize_categorical_columns(data):
    df = cudf.DataFrame(data)
    recreated = df.__class__.deserialize(*df.serialize())
    assert_eq(recreated, df)


@pytest.mark.parametrize(
    "data", [["$ 1", "$ 2", "hello"], ["($) 1", "( 2", "hello", "^1$"]]
)
@pytest.mark.parametrize("value", ["$ 1", "hello", "$", "^1$"])
def test_categorical_string_index_contains(data, value):
    idx = cudf.CategoricalIndex(data)
    pidx = idx.to_pandas()

    assert_eq(value in idx, value in pidx)
